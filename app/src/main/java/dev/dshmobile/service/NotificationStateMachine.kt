package dev.dshmobile.service

import dev.dshmobile.model.HostFrame
import dev.dshmobile.model.MuxFrame
import dev.dshmobile.model.SessionSummary
import dev.dshmobile.model.InteractionTarget
import dev.dshmobile.model.DshJson
import dev.dshmobile.model.QuestionItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 通知状态机（M3 核心，设计 §5.2 定稿：纯 Kotlin、无 Android 依赖、可 JVM 全分支单测）。
 *
 * 职责：消费 baseline 会话列表 + host/mux 帧流 + 本地取消事件，产出**通知指令流**
 * （Show/Cancel 命令），由 EventStreamService 执行真正的系统通知调用。
 * 纯逻辑与 Android 分离 → 时钟可注入 → 全分支确定性单测。
 *
 * 核心规则（全部对应设计 §3.2/§5.2，含评审修正）：
 * 1. 回合完成通知：仅内存快照 prev==true 且到达 running==false 时触发；
 *    冷启动（首次 baseline）静默；断连重连 baseline **补发**（P1 修正：堵漏报）；
 *    本地 cancel 后 3 秒抑制窗内不触发（P2：防误报）；origin==subagent 一律不触发（P1）。
 * 2. running 恢复为 true：撤该会话的回合完成通知 + 记录本地翻转时刻（§3.3 运行时长近似）。
 * 3. 审批：按 approvalId 键控；replay（同 approvalId 再达）只做静默更新不重响（P2）；
 *    subagent 会话的审批不通知。
 * 4. 提问：按信封层 rpcId 键控，语义同审批。
 * 5. approval/resolved / question/resolved：撤对应通知（幂等，覆盖"已在别处处理"）。
 */
class NotificationStateMachine(
    /** 可注入时钟（单测控制时间推进）；默认墙钟。 */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 会话内存快照（baseline 与帧流合并维护）。 */
    data class SessionState(
        var running: Boolean,
        var title: String? = null,
        var cwd: String? = null,
        var updatedAt: Double = 0.0,
        var blank: Boolean = false,
        var origin: String? = null,
        /** running 翻转为 true 的本地时刻（§3.3：运行时长近似，进程重启归零重计）。 */
        var localRunningSinceMillis: Long? = null,
    )

    /** 审批挂起项：rpcId 取信封层（应答时回显），键为 approvalId。 */
    data class ApprovalInfo(
        val rpcId: String,
        val sessionId: String,
        val approvalId: String,
        val toolName: String,
        val reason: String?,
        val target: InteractionTarget? = null,
        val available: Boolean = true,
    )

    /** 提问挂起项：键为信封层 rpcId（questions.schema：提问无独立资源 id，rpcId 即标识）。 */
    data class QuestionInfo(
        val rpcId: String,
        val sessionId: String,
        val firstQuestionText: String,
        val questions: List<dev.dshmobile.model.QuestionItem> = emptyList(),
        val target: InteractionTarget? = null,
        val available: Boolean = true,
        val callId: String? = null,
        val answerQueued: Boolean = false,
    ) {
        val key: String get() = callId?.let { "$sessionId:$it" } ?: rpcId
    }

    /** 通知指令：状态机的唯一输出。replay=true 表示键控去重命中的重放，执行层应静默更新。 */
    sealed interface Command {
        data class ShowTurnComplete(val sessionId: String, val displayTitle: String) : Command
        data class CancelTurnComplete(val sessionId: String) : Command
        data class ShowApproval(val info: ApprovalInfo, val replay: Boolean) : Command
        data class CancelApproval(val approvalId: String) : Command
        data class ShowQuestion(val info: QuestionInfo, val replay: Boolean) : Command
        data class CancelQuestion(val questionRpcId: String) : Command
    }

    private val sessions = LinkedHashMap<String, SessionState>()
    private val pendingApprovals = LinkedHashMap<String, ApprovalInfo>()
    private val pendingQuestions = LinkedHashMap<String, QuestionInfo>()
    private val cancelSuppressUntil = HashMap<String, Long>()
    private var coldStart = true
    private val questionProjectionSequences = HashMap<String, Long>()
    private var interactionRevision = 0L
    private val seenRemoteApprovalIds = HashSet<String>()
    private val notifiedQuestionKeys = HashSet<String>()
    private val submittedQuestionSequences = HashMap<String, Long>()
    private var interactionHostGeneration: Long? = null
    private var appliedQuestionProjectionRevision = 0L

    fun interactionRevision(): Long = interactionRevision
    fun questionProjectionRevision(): Long = appliedQuestionProjectionRevision

    /** 已归档会话集合（需求 2.9.3：不显示、不通知）。由 service 从 workspace.list / archived-sessions-changed 注入。 */
    private val archivedSessionIds = HashSet<String>()

    /** 注入归档集（service 在 baseline 与 host 帧时调用）。 */
    fun setArchivedSessionIds(ids: Collection<String>) {
        archivedSessionIds.clear()
        archivedSessionIds.addAll(ids)
    }

    fun isArchived(sessionId: String): Boolean = sessionId in archivedSessionIds

    companion object {
        /** 本地 cancel 抑制窗时长（设计 §3.2 P2：3 秒）。 */
        const val CANCEL_SUPPRESS_WINDOW_MILLIS = 3_000L
        private const val ORIGIN_SUBAGENT = "subagent"
        private const val TITLE_PROJECTION_KEY = "title"
    }

    // -------------------------------------------------------------------
    // 输入：baseline（session.list 快照）
    // -------------------------------------------------------------------

    /**
     * baseline 合并：首次调用 = 冷启动（全程静默）；之后每次调用 = 断连重连后的
     * 快照对账，落入 prev==true && 快照==false 的会话**补发**通知（P1 修正）。
     */
    fun onBaseline(items: List<SessionSummary>): List<Command> {
        val commands = mutableListOf<Command>()
        val now = clock()
        for (item in items) {
            val existing = sessions[item.sessionId]
            val prevRunning = existing?.running
            // 首见会话的锁存种子必须为 true（SessionState 默认 false 会让锁存永远压 false，
            // baseline 无法给冷启动会话置 blank——单测"running 清 blank"用例实测抓出）
            val state = existing ?: SessionState(running = item.running, blank = true)
            // 快照字段覆盖（baseline 是权威数据源；title 为空时保留已有值——投影可能滞后）
            state.running = item.running
            state.cwd = item.cwd ?: state.cwd
            state.updatedAt = item.updatedAt
            // blank 单向锁存（协作升级定稿）：一旦本机见过非 blank（running 清除过/宿主确认过），
            // baseline 不再写回 true——防"agent/status 推送先于 turn/start 写入会话事件表"的
            // 亚毫秒窗口与乱序快照把已转正会话打回隐藏。镜像宿主 applySessionListMetadata 单调语义。
            state.blank = state.blank && item.blank
            item.origin?.let { state.origin = it }
            item.titleOrNull()?.let { state.title = it }
            sessions[item.sessionId] = state

            if (item.running) {
                // 快照仍在跑：撤掉可能残留的回合完成通知（幂等），首次见到则记翻转时刻
                commands += Command.CancelTurnComplete(item.sessionId)
                if (prevRunning != true) state.localRunningSinceMillis = now
            } else if (prevRunning == true && state.origin != ORIGIN_SUBAGENT && !archivedSessionIds.contains(item.sessionId)) {
                // 断连窗口内完成的回合：补发判定（冷启动被 coldStart 标志挡下；归档会话不通知，需求 2.9.3）
                val suppressUntil = cancelSuppressUntil[item.sessionId] ?: 0L
                if (!coldStart && now >= suppressUntil) {
                    commands += Command.ShowTurnComplete(item.sessionId, state.displayTitle())
                }
            }
        }
        coldStart = false
        // 终审 P2-10：session.list 是权威全量（实测含 running/subagent 会话），
        // 快照中不在列表的会话 = 断连窗口内已消失 → 清除防幽灵（保留在列表的会话原样）
        val liveIds = items.mapTo(HashSet()) { it.sessionId }
        sessions.keys.retainAll(liveIds)
        return commands
    }

    // -------------------------------------------------------------------
    // 输入：host 流帧
    // -------------------------------------------------------------------

    fun onHostFrame(frame: HostFrame): List<Command> {
        return when (frame) {
            is HostFrame.SessionStatus -> onSessionStatus(frame)
            is HostFrame.SessionAdded -> {
                // 会话新增帧改为"合并"而非整体替换（协作升级定稿）：整体替换会丢掉已有状态
                // （running/标题/updatedAt），且把 blank 写回 true——乱序/重放场景下已转正的会话
                // 会被打回隐藏。合并语义：已有状态优先，帧只补缺。
                val existing = sessions[frame.sessionId]
                sessions[frame.sessionId] = SessionState(
                    running = existing?.running ?: false,
                    title = existing?.title,
                    cwd = frame.cwd ?: existing?.cwd,
                    updatedAt = existing?.updatedAt ?: 0.0,
                    blank = existing?.blank ?: frame.blank,
                    origin = frame.origin ?: existing?.origin,
                    localRunningSinceMillis = existing?.localRunningSinceMillis,
                )
                emptyList()
            }
            is HostFrame.SessionRemoved -> {
                sessions.remove(frame.sessionId)
                emptyList()
            }
            // agent 错误走列表徽标不弹通知（设计 §5.2：避免与完成通知叠加）；workspace/archived/remote 帧由 Repository 层消费
            is HostFrame.AgentError,
            is HostFrame.WorkspaceChanged,
            is HostFrame.WorkspaceRemoved,
            is HostFrame.WorkspaceOrderChanged,
            is HostFrame.ArchivedSessionsChanged,
            is HostFrame.RemoteEvent,
            is HostFrame.StreamError,
            is HostFrame.Unknown,
            -> emptyList()
        }
    }

    /** running 翻转核心分支（通知①的触发点）。 */
    private fun onSessionStatus(frame: HostFrame.SessionStatus): List<Command> {
        val commands = mutableListOf<Command>()
        val existing = sessions[frame.sessionId]
        val prevRunning = existing?.running
        val state = existing ?: SessionState(running = frame.running)
        sessions[frame.sessionId] = state

        if (frame.running) {
            state.running = true
            if (prevRunning != true) state.localRunningSinceMillis = clock()
            // running=true ⇒ 必有 turn/start ⇒ 会话不再 blank（修复：新建会话发出首条消息后，
            // 快照 blank 仍为 true 导致列表一直隐藏该会话——baseline 未重拉时永不自愈）。
            state.blank = false
            // 恢复运行：撤回合完成通知（含覆盖陈旧通知的场景）
            commands += Command.CancelTurnComplete(frame.sessionId)
        } else {
            state.running = false
            state.localRunningSinceMillis = null
            if (prevRunning == true && !coldStart && state.origin != ORIGIN_SUBAGENT && !archivedSessionIds.contains(frame.sessionId)) {
                val suppressUntil = cancelSuppressUntil[frame.sessionId] ?: 0L
                if (clock() >= suppressUntil) {
                    commands += Command.ShowTurnComplete(frame.sessionId, state.displayTitle())
                }
            }
        }
        return commands
    }

    // -------------------------------------------------------------------
    // 输入：mux 流帧（rpcId 为信封层 id）
    // -------------------------------------------------------------------

    fun onMuxFrame(envelopeRpcId: String, frame: MuxFrame): List<Command> {
        return when (frame) {
            is MuxFrame.ApprovalRequested -> {
                // subagent 会话的审批不通知（P1）；键控 approvalId，replay 只静默更新（P2）
                if (sessions[frame.sessionId]?.origin == ORIGIN_SUBAGENT) return emptyList()
                val info = ApprovalInfo(
                    rpcId = envelopeRpcId,
                    sessionId = frame.sessionId,
                    approvalId = frame.approvalId,
                    toolName = frame.toolName,
                    reason = frame.reason,
                )
                val replay = pendingApprovals.containsKey(frame.approvalId)
                pendingApprovals[frame.approvalId] = info
                listOf(Command.ShowApproval(info, replay))
            }
            is MuxFrame.ApprovalResolved -> {
                // 幂等撤通知：覆盖本机通知尚在但别处已处理的场景（含 cancelled/unavailable）
                pendingApprovals.remove(frame.approvalId)
                listOf(Command.CancelApproval(frame.approvalId))
            }
            is MuxFrame.QuestionRequested -> {
                if (sessions[frame.sessionId]?.origin == ORIGIN_SUBAGENT) return emptyList()
                val info = QuestionInfo(
                    rpcId = envelopeRpcId,
                    sessionId = frame.sessionId,
                    firstQuestionText = frame.questions.firstOrNull()?.question.orEmpty(),
                    questions = frame.questions,
                )
                val replay = pendingQuestions.containsKey(envelopeRpcId)
                pendingQuestions[envelopeRpcId] = info
                listOf(Command.ShowQuestion(info, replay))
            }
            is MuxFrame.QuestionResolved -> {
                pendingQuestions.remove(frame.questionRpcId)
                listOf(Command.CancelQuestion(frame.questionRpcId))
            }
            is MuxFrame.SessionProjection -> {
                // 标题投影增量：影响后续通知的 displayTitle
                if (frame.key == TITLE_PROJECTION_KEY) {
                    sessions[frame.sessionId]?.title = (frame.value as? kotlinx.serialization.json.JsonPrimitive)?.content
                }
                emptyList()
            }
            // 对话细节/队列/任务帧由 WebView 内 GUI 消费，双份消费无必要（设计 §5.2）
            is MuxFrame.SessionEvent,
            is MuxFrame.SessionSubscribed,
            is MuxFrame.SessionQueue,
            is MuxFrame.SessionJobs,
            is MuxFrame.StreamError,
            is MuxFrame.Unknown,
            -> emptyList()
        }
    }

    // -------------------------------------------------------------------
    // 输入：现代交互（不按历史子会话来源过滤，宿主已校验实时归属）
    // -------------------------------------------------------------------

    /** 换宿主后，旧调用地址、投影游标和去重记录不能影响新宿主的同名会话。 */
    fun onInteractionHostGeneration(generation: Long): List<Command> {
        val previous = interactionHostGeneration
        interactionHostGeneration = generation
        if (previous == null || previous == generation) return emptyList()
        interactionRevision++
        val commands = pendingApprovals.values.map { Command.CancelApproval(it.approvalId) } +
            pendingQuestions.values.map { Command.CancelQuestion(it.rpcId) }
        pendingApprovals.clear()
        pendingQuestions.clear()
        questionProjectionSequences.clear()
        submittedQuestionSequences.clear()
        seenRemoteApprovalIds.clear()
        notifiedQuestionKeys.clear()
        return commands
    }

    fun onRemoteApproval(info: ApprovalInfo): List<Command> {
        interactionRevision++
        val replay = !seenRemoteApprovalIds.add(info.approvalId)
        pendingApprovals[info.approvalId] = info
        return listOf(Command.ShowApproval(info, replay))
    }

    fun onRemoteQuestion(info: QuestionInfo): List<Command> {
        interactionRevision++
        val previous = pendingQuestions.put(info.key, info)
        return buildList {
            if (previous != null && previous.rpcId != info.rpcId) add(Command.CancelQuestion(previous.rpcId))
            if (info.available && !info.answerQueued) {
                add(Command.ShowQuestion(info, !notifiedQuestionKeys.add(info.key)))
            } else if (previous != null) add(Command.CancelQuestion(previous.rpcId))
        }
    }

    /** 新连接会重放仍待答的事件；已失效的旧审批和非限时问题不能永久残留。 */
    fun onRemoteReady(): List<Command> {
        interactionRevision++
        val commands = mutableListOf<Command>()
        for (info in pendingApprovals.values.filter { !it.available }) {
            pendingApprovals.remove(info.approvalId)
            commands += Command.CancelApproval(info.approvalId)
        }
        for (info in pendingQuestions.values.filter { !it.available && it.callId == null }) {
            pendingQuestions.remove(info.key)
            commands += Command.CancelQuestion(info.rpcId)
        }
        return commands
    }

    /** 只停用同一连接的地址，迟到的旧代断线不能撤销新代重放。 */
    fun onRemoteDisconnected(clientId: String): List<Command> {
        interactionRevision++
        val commands = mutableListOf<Command>()
        pendingApprovals.replaceAll { _, info ->
            if ((info.target as? InteractionTarget.Remote)?.clientId == clientId) {
                commands += Command.CancelApproval(info.approvalId)
                info.copy(target = null, available = false)
            } else info
        }
        pendingQuestions.replaceAll { _, info ->
            if ((info.target as? InteractionTarget.Remote)?.clientId == clientId) {
                commands += Command.CancelQuestion(info.rpcId)
                info.copy(target = null, available = false)
            } else info
        }
        return commands
    }

    fun onRemoteCancelled(target: InteractionTarget.Remote): List<Command> {
        interactionRevision++
        val commands = mutableListOf<Command>()
        val approvals = pendingApprovals.values.filter { it.target == target }
        for (info in approvals) {
            pendingApprovals.remove(info.approvalId)
            commands += Command.CancelApproval(info.approvalId)
        }
        val questions = pendingQuestions.values.filter { it.target == target }
        for (info in questions) {
            commands += Command.CancelQuestion(info.rpcId)
            // 限时请求结束后仍可能进入稍后回答；保留稳定卡片及页面草稿，交由投影裁定。
            if (info.callId != null) pendingQuestions[info.key] = info.copy(target = null, available = false)
            else pendingQuestions.remove(info.key)
        }
        return commands
    }

    fun onContinuedSubmitted(target: InteractionTarget.Continued): List<Command> {
        interactionRevision++
        val info = pendingQuestions.values.firstOrNull { it.target == target } ?: return emptyList()
        submittedQuestionSequences[info.key] = questionProjectionSequences[info.sessionId] ?: Long.MIN_VALUE
        pendingQuestions[info.key] = info.copy(available = false, answerQueued = true)
        return listOf(Command.CancelQuestion(info.rpcId))
    }

    /** 缺少或畸形投影不是空快照；只对有完整 userQuestions 的会话做撤卡。 */
    fun onQuestionProjection(
        sessionId: String,
        values: JsonObject,
        asOfSeq: Long? = null,
        hostGeneration: Long? = null,
    ): List<Command> {
        val projection = values["userQuestions"] as? JsonObject ?: return emptyList()
        val active = projection["active"] as? JsonArray ?: return emptyList()
        if (asOfSeq != null && asOfSeq < (questionProjectionSequences[sessionId] ?: Long.MIN_VALUE)) return emptyList()
        val parsed = active.map { element ->
            val item = element as? JsonObject ?: return emptyList()
            val callId = (item["callId"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return emptyList()
            val state = (item["state"] as? JsonPrimitive)?.contentOrNull
            if (state != "open" && state != "continued") return emptyList()
            val questions = parseQuestions(item["questions"] as? JsonArray ?: return emptyList())
                ?: return emptyList()
            Triple(callId, state, questions)
        }
        if (asOfSeq != null) questionProjectionSequences[sessionId] = asOfSeq
        val inbox = values["inbox"] as? JsonObject
        val completeInbox = inbox != null && inbox["next-step"] is JsonArray && inbox["next-turn"] is JsonArray
        if (completeInbox) appliedQuestionProjectionRevision++
        val queuedCalls = listOf("next-step", "next-turn").flatMap { placement ->
            (inbox?.get(placement) as? JsonArray).orEmpty().mapNotNull { element ->
                val source = (element as? JsonObject)?.get("source") as? JsonObject
                if ((source?.get("kind") as? JsonPrimitive)?.contentOrNull == "user-question-reply") {
                    (source?.get("callId") as? JsonPrimitive)?.contentOrNull
                } else null
            }
        }.toSet()
        val commands = mutableListOf<Command>()
        val liveKeys = parsed.mapTo(HashSet()) { "$sessionId:${it.first}" }
        for (old in pendingQuestions.values.toList()) {
            if (old.sessionId == sessionId && old.callId != null && old.key !in liveKeys) {
                // 活动事件的取消由现代流确认，不能让较早采样的空投影撤掉新问题。
                if (old.target is InteractionTarget.Remote) continue
                val submittedAt = submittedQuestionSequences[old.key]
                if (submittedAt != null && (asOfSeq == null || asOfSeq <= submittedAt)) continue
                pendingQuestions.remove(old.key)
                submittedQuestionSequences.remove(old.key)
                commands += Command.CancelQuestion(old.rpcId)
            }
        }
        for ((callId, state, questions) in parsed) {
            val key = "$sessionId:$callId"
            val existing = pendingQuestions[key]
            // open 投影不提供活动回答地址，不能把仍在 attachWait 的现代地址覆盖为 continued。
            if (state == "open") continue
            val submittedAt = submittedQuestionSequences[key]
            if (submittedAt != null && asOfSeq != null && asOfSeq > submittedAt &&
                completeInbox && callId !in queuedCalls
            ) {
                // 相同游标的空收件箱可能早于本地提交；只有后续权威快照能解除防重复状态。
                submittedQuestionSequences.remove(key)
            }
            val queued = callId in queuedCalls || key in submittedQuestionSequences ||
                (!completeInbox && existing?.answerQueued == true)
            val mergedQuestions = questions.map { question ->
                val previous = existing?.questions?.find { it.id == question.id }
                question.copy(detail = question.detail ?: previous?.detail, intent = question.intent ?: previous?.intent)
            }
            val info = QuestionInfo(
                rpcId = existing?.rpcId ?: key,
                sessionId = sessionId,
                firstQuestionText = mergedQuestions.first().question,
                questions = mergedQuestions,
                target = InteractionTarget.Continued(sessionId, callId, hostGeneration),
                available = !queued,
                callId = callId,
                answerQueued = queued,
            )
            commands += onRemoteQuestion(info)
        }
        return commands
    }

    private fun parseQuestions(array: JsonArray): List<QuestionItem>? = try {
        array.map { DshJson.decodeFromJsonElement(QuestionItem.serializer(), it) }
            .takeIf { it.isNotEmpty() && it.all { question -> question.id.isNotBlank() && question.question.isNotBlank() } }
    } catch (_: Exception) { null }

    // -------------------------------------------------------------------
    // 输入：本地事件
    // -------------------------------------------------------------------

    /** APP 内停止按钮调用后登记抑制窗（P2：cancel 导致的 running:false 不触发完成通知）。 */
    fun onLocalCancel(sessionId: String) {
        cancelSuppressUntil[sessionId] = clock() + CANCEL_SUPPRESS_WINDOW_MILLIS
    }

    // -------------------------------------------------------------------
    // 只读视图（Repository/UI 消费）
    // -------------------------------------------------------------------

    fun sessionSnapshot(): Map<String, SessionState> {
        // 需求 2.9.3：归档会话不进入展示快照（列表隐藏、不提示）
        return LinkedHashMap(sessions).apply {
            keys.removeAll(archivedSessionIds)
            // 状态流按相等性去重；发布值不能与内部可变对象共享，否则结束变化会被吞掉。
            entries.forEach { entry -> entry.setValue(entry.value.copy()) }
        }
    }

    fun pendingApproval(id: String): ApprovalInfo? = pendingApprovals[id]

    fun pendingQuestion(rpcId: String): QuestionInfo? =
        pendingQuestions[rpcId] ?: pendingQuestions.values.firstOrNull { it.rpcId == rpcId }

    /** 按会话分组的挂起审批（供对话页内联审批卡）。 */
    fun snapshotApprovals(): Map<String, List<ApprovalInfo>> =
        pendingApprovals.values.groupBy { it.sessionId }

    /** 按会话分组的挂起提问（供对话页内联提问卡）。 */
    fun snapshotQuestions(): Map<String, List<QuestionInfo>> =
        pendingQuestions.values.groupBy { it.sessionId }

    // -------------------------------------------------------------------
    // 内部
    // -------------------------------------------------------------------

    /** 通知标题：投影标题 → cwd 尾段 → 兜底"会话"。 */
    private fun SessionState.displayTitle(): String {
        title?.takeIf { it.isNotBlank() }?.let { return it }
        cwd?.takeIf { it.isNotBlank() }?.let {
            val tail = it.substringAfterLast('\\').substringAfterLast('/')
            if (tail.isNotBlank()) return tail
        }
        return "会话"
    }
}
