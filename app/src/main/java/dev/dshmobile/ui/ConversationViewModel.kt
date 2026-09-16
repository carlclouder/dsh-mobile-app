package dev.dshmobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.dshmobile.BuildConfig
import dev.dshmobile.DshRepository
import dev.dshmobile.model.ApiResult
import dev.dshmobile.model.ConversationEvent
import dev.dshmobile.service.NotificationStateMachine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.util.Log

/**
 * 会话对话页 ViewModel（M5 原生：M5 修订后由 WebView 改为原生 Compose 渲染）。
 *
 * 数据三来源：
 * 1. 打开时 sessionHistory() 拉历史消息（user/assistant/tool）
 * 2. mux 流 session/event 帧（DshRepository.sessionEvents 分流）实时追加
 * 3. repository 会话快照驱动 running（"正在思考…" + 停止按钮显隐）
 *
 * 渲染单位：UiMessage（含 role / 正文 / 推理摘要 / 工具调用 / 工具错误标记）。
 */
class ConversationViewModel(private val sessionId: String) : ViewModel() {

    enum class Role { USER, ASSISTANT, TOOL, CONTEXT, ERROR }

    /** 工具结果的展示单元：正文 + 宿主单行摘要（WebUI 紧凑渲染对齐，null=本地兜底首行）。 */
    data class ToolResultDisplay(
        val text: String,
        val viewTitle: String? = null,
    )

    data class UiMessage(
        val key: String,
        val role: Role,
        val text: String,
        val reasoning: String? = null,
        val toolCalls: List<ConversationEvent.ToolCall> = emptyList(),
        val toolResults: List<ToolResultDisplay> = emptyList(),
        val toolError: Boolean = false,
        val timeMillis: Long = 0,
        val contextRole: String? = null,   // 仅 Role.CONTEXT："inject"=注入 | "recall"=召回（决定图标与文案）
        val contextLabel: String? = null,  // 仅 Role.CONTEXT：来源名（文件路径/插件名等），null=不显示
        val toolViewTitle: String? = null, // 仅 Role.TOOL 独立结果消息：宿主单行摘要
    )

    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /**
     * 历史加载完成标志（滚底修复）：首滚必须等它为 true 才执行——消息列表是多波异步填充的
     * （实时事件可能先于 loadHistory 到达），提前定位会被少量先到消息消耗掉，历史大列表
     * 到位后 isNearBottom=false 就再也不滚（"进会话不滚底"根因之一）。三个结束分支都置 true：
     * 加载失败也放行首滚，避免永不定位。
     */
    private val _historyLoaded = MutableStateFlow(false)
    val historyLoaded: StateFlow<Boolean> = _historyLoaded.asStateFlow()

    /** 上下文用量统计（需求：上下文使用统计），assistant/chunk 的 usage 累加。 */
    data class TokenStats(val input: Int = 0, val output: Int = 0, val cache: Int = 0)
    private val _tokenStats = MutableStateFlow(TokenStats())
    val tokenStats: StateFlow<TokenStats> = _tokenStats.asStateFlow()

    /** 上下文已用 token（需求：上下文已用 xx%）= 最近一轮 usage 的 cache+input。 */
    private val _contextUsedTokens = MutableStateFlow(0)
    val contextUsedTokens: StateFlow<Int> = _contextUsedTokens.asStateFlow()

    /** 模型目录 + 当前选择（需求：模型/推理等级选择器）。 */
    private val _models = MutableStateFlow<dev.dshmobile.model.ModelDirectory?>(null)
    val models: StateFlow<dev.dshmobile.model.ModelDirectory?> = _models.asStateFlow()

    private val _currentModel = MutableStateFlow<dev.dshmobile.model.ModelSelection?>(null)
    val currentModel: StateFlow<dev.dshmobile.model.ModelSelection?> = _currentModel.asStateFlow()

    // 挂起审批/提问/排队消息（必须在 init 之前声明：它们从 StateFlow collect，collect 会立即发当前值，
    // 若声明在 init 之后，Main.immediate 启动的协程会在字段未初始化前同步执行 → NPE。
    // 排队消息流改为 StateFlow 快照镜像后同样受此约束——这是"点会话闪退"bug 的根因）
    private val _approvals = MutableStateFlow<List<NotificationStateMachine.ApprovalInfo>>(emptyList())
    val approvals: StateFlow<List<NotificationStateMachine.ApprovalInfo>> = _approvals.asStateFlow()
    private val _questions = MutableStateFlow<List<NotificationStateMachine.QuestionInfo>>(emptyList())
    val questions: StateFlow<List<NotificationStateMachine.QuestionInfo>> = _questions.asStateFlow()

    /** 排队消息（需求：编辑/删除）。 */
    private val _queuedItems = MutableStateFlow<List<dev.dshmobile.model.QueueItem>>(emptyList())
    val queuedItems: StateFlow<List<dev.dshmobile.model.QueueItem>> = _queuedItems.asStateFlow()

    /**
     * 插话中的排队项 id（修复"插话后可连点重复发送"）：发出即标记、按钮禁用；
     * host 回显把项移出会话队列（任意 placement 都不再存在）后自动解除。
     */
    private val _steeringItemIds = MutableStateFlow<Set<String>>(emptySet())
    val steeringItemIds: StateFlow<Set<String>> = _steeringItemIds.asStateFlow()

    init {
        viewModelScope.launch { loadHistory() }
        viewModelScope.launch { collectLiveEvents() }
        viewModelScope.launch { loadModels() }
        viewModelScope.launch { collectQueue() }
        viewModelScope.launch { collectStats() }
        viewModelScope.launch { collectApprovals() }
        viewModelScope.launch { collectQuestions() }
        // 流式节流刷新器：把高频 chunk 合并成低频 _messages.value 更新（修复 P0 卡死）
        startStreamFlusher()
        // running 来自 repository 会话快照（host/session-status 推送）
        viewModelScope.launch {
            DshRepository.sessions.collect { snap ->
                _running.value = snap[sessionId]?.running == true
            }
        }
    }

    /** 会话统计（底部信息栏）。 */
    private val _sessionStats = MutableStateFlow<dev.dshmobile.model.SessionStats?>(null)
    val sessionStats: StateFlow<dev.dshmobile.model.SessionStats?> = _sessionStats.asStateFlow()

    private suspend fun collectStats() {
        DshRepository.sessionStats.collect { (sid, stats) ->
            if (sid == sessionId) _sessionStats.value = stats
        }
    }

    /** 会话当前任务列表（需求：当前会话任务列表栏——todo/write 事件驱动）。 */
    private val _todos = MutableStateFlow<List<dev.dshmobile.model.ConversationEvent.TodoItem>>(emptyList())
    val todos: StateFlow<List<dev.dshmobile.model.ConversationEvent.TodoItem>> = _todos.asStateFlow()

    private suspend fun collectApprovals() {
        DshRepository.pendingApprovals.collect { map ->
            _approvals.value = map[sessionId] ?: emptyList()
        }
    }

    private suspend fun collectQuestions() {
        DshRepository.pendingQuestions.collect { map ->
            _questions.value = map[sessionId] ?: emptyList()
        }
    }

    /**
     * 应答审批：允许一次 / 拒绝。乐观移除卡片 + 回执三态收敛（RespondReceiptPolicy）：
     * 受理/别处已处理维持移除；传输失败恢复卡片可重试——修复"应答失败卡片静默消失、审批永挂"
     * 的死局路径（三态语义对齐 ApprovalReceiver 通知路径）。
     */
    fun respondApproval(rpcId: String, approvalId: String, allow: Boolean) {
        // 移除前留存：应答失败时恢复卡片的依据
        val dismissed = _approvals.value.firstOrNull { it.approvalId == approvalId }
        _approvals.value = _approvals.value.filter { it.approvalId != approvalId }
        viewModelScope.launch {
            when (RespondReceiptPolicy.decide(
                DshRepository.apiClient.respondApproval(rpcId, sessionId, approvalId, allow),
            )) {
                RespondReceiptPolicy.Decision.ACCEPTED -> Unit
                RespondReceiptPolicy.Decision.ALREADY_HANDLED ->
                    _actionError.value = "该审批已在别处处理。"
                RespondReceiptPolicy.Decision.RETRY -> restoreApprovalCard(dismissed, approvalId)
            }
        }
    }

    /** 审批应答失败：恢复卡片（判重防与帧同步加回的重复）+ Toast 引导重试。 */
    private fun restoreApprovalCard(dismissed: NotificationStateMachine.ApprovalInfo?, approvalId: String) {
        if (dismissed != null && _approvals.value.none { it.approvalId == approvalId }) {
            _approvals.value = _approvals.value + dismissed
        }
        _actionError.value = "审批应答失败，请重试。"
    }

    /** 应答提问：提交选择/自定义答案。回执三态收敛同 respondApproval（修复"回答丢失 + 会话永久卡住"）。 */
    fun respondQuestion(rpcId: String, answers: List<dev.dshmobile.model.QuestionAnswerItem>) {
        val dismissed = _questions.value.firstOrNull { it.rpcId == rpcId }
        _questions.value = _questions.value.filter { it.rpcId != rpcId }
        viewModelScope.launch {
            when (RespondReceiptPolicy.decide(
                DshRepository.apiClient.respondQuestion(rpcId, sessionId, answers),
            )) {
                RespondReceiptPolicy.Decision.ACCEPTED -> Unit
                RespondReceiptPolicy.Decision.ALREADY_HANDLED ->
                    _actionError.value = "该提问已在别处处理。"
                RespondReceiptPolicy.Decision.RETRY -> restoreQuestionCard(dismissed, rpcId)
            }
        }
    }

    /** 提问应答失败：恢复卡片（判重防重复）+ Toast 引导重试。 */
    private fun restoreQuestionCard(dismissed: NotificationStateMachine.QuestionInfo?, rpcId: String) {
        if (dismissed != null && _questions.value.none { it.rpcId == rpcId }) {
            _questions.value = _questions.value + dismissed
        }
        _actionError.value = "回答发送失败，请重试。"
    }

    private suspend fun collectQueue() {
        // sessionQueue 是 StateFlow 快照镜像（DshRepository）：进页面立即收到当前值，
        // 排队区不再依赖"恰好有新帧"——插话/编辑/删除后的收敛完全由 host 回显驱动（对齐 WebUI）
        DshRepository.sessionQueue.collect { map ->
            val snapshot = map[sessionId] ?: emptyList()
            // 只显示等待处理(queued)的项；steering(已插话待消费) 与 context(注入) 不当"排队中"显示
            _queuedItems.value = snapshot.filter { it.placement == "queued" }
            // 插话中标记的收敛：项已从会话队列整体消失（host 已消费/移除）→ 解除标记；
            // 仍以任意 placement 存在（如 steer 待消费）→ 保持禁用，防止重复插话
            val liveIds = snapshot.map { it.id }.toSet()
            _steeringItemIds.value = _steeringItemIds.value.filterTo(HashSet()) { it in liveIds }
        }
    }

    /** 编辑排队消息（失败文案对齐 WebUI queue.editFailed：提示"可能已开始发送"的具体语义）。 */
    fun editQueued(itemId: String, newText: String) {
        viewModelScope.launch {
            when (DshRepository.apiClient.sessionUpdateQueue(
                sessionId, itemId, DshRepository.apiClient.queueEditAction(newText),
            )) {
                is ApiResult.Ok -> Unit
                else -> _actionError.value = "编辑失败：这条消息可能已经开始发送。"
            }
        }
    }

    /** 删除排队消息（与插话一致：本地不乐观删除，纯 host 回显驱动；失败文案对齐 WebUI queue.removeFailed）。 */
    fun deleteQueued(itemId: String) {
        viewModelScope.launch {
            when (DshRepository.apiClient.sessionUpdateQueue(
                sessionId, itemId, DshRepository.apiClient.queueRemoveAction(),
            )) {
                is ApiResult.Ok -> Unit
                else -> _actionError.value = "删除失败：这条消息可能已经开始发送。"
            }
        }
    }

    /** 操作失败提示（UI 收集弹 Toast；null=无）。插话/编辑/删除共用一条通道（对齐 WebUI 统一失败通知）。 */
    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    fun clearActionError() { _actionError.value = null }

    /**
     * 插话（steer）：把指定排队项就地提升为 steer，让 host 在下一步边界消费（与 WebUI 每行"插话发送"对齐）。
     * 修复"插话后可连点重复发送"：排队项去留仍由 host 回显驱动（不做乐观删除，避免旧帧加回的闪烁），
     * 但发出瞬间把该项标记为"插话中"并禁用按钮——回显把它从队列移除后自动解除标记；
     * 失败（可重试类）解除标记恢复可点。连点在客户端就被挡住，不会发出两条。
     */
    fun steer(itemId: String, text: String) {
        if (text.isBlank() || !_running.value) return
        // 插话中的项直接忽略（双击/误连点防护）
        if (itemId in _steeringItemIds.value) return
        _steeringItemIds.value = _steeringItemIds.value + itemId
        viewModelScope.launch {
            when (val r = DshRepository.apiClient.sessionUpdateQueue(
                sessionId, itemId, DshRepository.apiClient.queueSteerAction(),
            )) {
                // 受理成功：保持标记（等待 host 回显把该项移出队列后由 collectQueue 解除）；
                // 若回显迟迟未到（极端网络），项仍在队列且按钮禁用——不会重复发送，代价可控。
                is ApiResult.Ok -> Unit
                is ApiResult.BizError ->
                    if (r.code == "steer-unavailable" || r.code == "queue-item-not-found") {
                        // 未生效（回合窗口关闭/项已被消费）：解除标记——steer-unavailable 时项仍在队列，
                        // 必须恢复可点允许重试；不解除会让该项按钮永久禁用
                        _steeringItemIds.value = _steeringItemIds.value - itemId
                        Unit
                    } else {
                        _steeringItemIds.value = _steeringItemIds.value - itemId
                        _actionError.value = "插话发送失败，请重试。"
                    }
                is ApiResult.NetError -> {
                    _steeringItemIds.value = _steeringItemIds.value - itemId
                    _actionError.value = "插话发送失败，请重试。"
                }
            }
        }
    }

    /** 拉模型目录（首次/会话打开时机；失败不清空旧目录）。 */
    fun loadModels() {
        viewModelScope.launch {
            when (val r = DshRepository.apiClient.sessionModels(sessionId)) {
                is ApiResult.Ok -> {
                    _models.value = r.value
                    _currentModel.value = r.value.current
                }
                is ApiResult.BizError -> Log.w(TAG, "models biz: ${r.code}")
                is ApiResult.NetError -> Log.w(TAG, "models net: ${r.throwable.message}")
            }
        }
    }

    /** 选择模型/推理等级。 */
    fun selectModel(provider: String, model: String, reasoningEffort: String?) {
        viewModelScope.launch {
            val pick = dev.dshmobile.model.ModelSelection(provider, model, reasoningEffort)
            _currentModel.value = pick   // 乐观更新
            when (DshRepository.apiClient.sessionSelectModel(sessionId, provider, model, reasoningEffort)) {
                is ApiResult.Ok -> Unit
                is ApiResult.BizError -> Log.w(TAG, "selectModel biz")
                is ApiResult.NetError -> Log.w(TAG, "selectModel net")
            }
        }
    }

    /**
     * 轮询刷新（新版 dsh ≥0.1.2-rc.1 移除了 WS 事件流，会话页实时性以周期 history 对账替代）：
     * 由会话页在"本会话运行中"期间每 2 秒调用一次；loadHistory 是合并式可重入（P1-1 修复保证
     * 往返期间实时消息不被抹），反复调用安全。
     */
    fun refreshViaPolling() {
        viewModelScope.launch { loadHistory() }
    }

    private suspend fun loadHistory() {
        when (val r = DshRepository.apiClient.sessionHistory(sessionId)) {
            is ApiResult.Ok -> {
                var input = 0; var output = 0; var cache = 0
                val msgs = ArrayList<UiMessage>()
                // 历史重放不需要流式片段（assistant/chunk）；过滤掉，避免一次性遍历上万事件 + distinctBy 去重开销
                r.value
                    .filterNot { it is ConversationEvent.AssistantChunkDelta }
                    .forEach { ev ->
                    when (ev) {
                        // 历史里的 usage chunk → 累计用量（上下文使用统计）
                        is ConversationEvent.ContextUsage -> {
                            input += ev.inputTokens; output += ev.outputTokens
                            cache += ev.cacheReadTokens ?: 0
                        }
                        // 历史里的 todo/write → 更新当前任务列表（不渲染成消息）
                        is ConversationEvent.TodoUpdate -> { _todos.value = ev.todos }
                        // 历史里的错误结尾（额度用完/模型API失败/中断）：渲染为错误提示
                        is ConversationEvent.TurnEnd -> {
                            ev.reason?.let { msgs.add(errorUiMessage(it)) }
                        }
                        // 工具结果：合并进上一条带工具调用的助手消息（工具调用+结果合成一条）
                        is ConversationEvent.ToolResult -> {
                            val merged = appendToolResult(msgs, ev)
                            msgs.clear(); msgs.addAll(merged)
                        }
                        else -> ev.toUiMessage()?.let { msgs.add(it) }
                    }
                }
                // P1-1 修复：不整表覆盖 _messages（避免 history 网络往返期间已 upsert 的实时尾部消息被抹掉）。
                // 用已有列表 + 历史消息按 key 合并（历史去重，保留实时/流式消息），远端顺序稳定。
                val merged = LinkedHashMap<String, UiMessage>()
                // 先放现有的（实时已 upsert 的，含流式占位/乐观本地），再 merge 历史，历史不覆盖已存在的 key
                _messages.value.forEach { merged[it.key] = it }
                msgs.filterNot { it.key == "__streaming__" }.forEach { merged.putIfAbsent(it.key, it) }
                _messages.value = merged.values.toList()
                _tokenStats.value = TokenStats(input, output, cache)
                _historyLoaded.value = true
            }
            is ApiResult.BizError -> {
                Log.w(TAG, "history biz error: ${r.code}")
                _historyLoaded.value = true   // 失败也放行首滚（现有消息仍可定位到最新）
            }
            is ApiResult.NetError -> {
                Log.w(TAG, "history net error: ${r.throwable.message}")
                _historyLoaded.value = true
            }
        }
    }

    private suspend fun collectLiveEvents() {
        DshRepository.sessionEvents.collect { (sid, ev) ->
            if (sid != sessionId) return@collect
            when (ev) {
                is ConversationEvent.TurnStart -> {
                    _running.value = true
                    // 新 turn 开始：清理上一 turn 可能残留的 __streaming__ 占位（其半截内容用户已看过，属旧 turn 尾巴）。
                    // 正常路径该占位早已被 settled AssistantMessage 替换删除，此处是中断场景的兜底清理，避免新旧占位混叠。
                    flushStream()
                    _messages.value = _messages.value.filter { it.key != STREAMING_KEY }
                }
                is ConversationEvent.TurnEnd -> {
                    _running.value = false
                    // 收尾：把残留流式缓冲刷进 __streaming__ 占位（保留已生成文本）。
                    // 注意：不在此处删占位——若正常渠道，随后 settled AssistantMessage 会刷占位+删占位+替换成正式消息；
                    // 若被中断(用户点停止/turn 中途 cancel，服务端只发 turn/end 而无物化 assistant/message)，保留占位可避免
                    // 已生成的半截助手正文从界面凭空消失（评审 P1）。
                    flushStream()
                    // 错误原因（额度用完/模型API失败/中断异常）：渲染为错误提示，用户不致误以为消息未发出
                    ev.reason?.let { appendAgentError(it) }
                }
                is ConversationEvent.AgentError -> appendAgentError(ev.message)
                is ConversationEvent.AssistantChunkDelta -> aggregateStreaming(ev)
                is ConversationEvent.AssistantMessage -> {
                    // settled 消息到达：先刷残留流式缓冲 + 移除流式占位，再按 key upsert（历史+实时重叠去重）
                    flushStream()
                    ev.toUiMessage()?.let { finalMsg ->
                        _messages.value = _messages.value.filter { it.key != STREAMING_KEY }
                        upsertMessage(msg = finalMsg)
                    }
                }
                is ConversationEvent.UserMessage -> {
                    // 去重：send() 乐观本地消息 + 服务端回显（文本替换）；历史/实时重叠按 key 去重
                    ev.toUiMessage()?.let { upsertMessage(msg = it) }
                }
                is ConversationEvent.ContextUsage -> {
                    // 上下文用量累计（输入/输出/缓存）
                    val s = _tokenStats.value
                    _tokenStats.value = TokenStats(
                        input = s.input + ev.inputTokens,
                        output = s.output + ev.outputTokens,
                        cache = s.cache + (ev.cacheReadTokens ?: 0),
                    )
                }
                is ConversationEvent.TodoUpdate -> { _todos.value = ev.todos }
                is ConversationEvent.ToolResult -> {
                    // 工具结果合并进上一条带工具调用的助手消息（工具调用+结果合成一条）
                    _messages.value = appendToolResult(_messages.value, ev)
                }
                else -> ev.toUiMessage()?.let { upsertMessage(msg = it) }
            }
        }
    }

    /**
     * 把工具结果合并进【上一条带工具调用的助手消息】的 toolResults；若上一条不是助手（罕见），
     * 则作为独立的工具结果消息追加。这样"工具调用 + 结果"在界面合成一条。
     */
    /**
     * 模型/代理调用错误（额度用完/API失败/中断）：追加为会话内错误提示。
     * 去重：与最新的错误提示同文本则跳过（agent-error 帧与 turn/end 原因可能同源各发一次）。
     */
    private fun appendAgentError(message: String) {
        val list = _messages.value
        val last = list.lastOrNull()
        if (last?.role == Role.ERROR && last.text == message) return
        _messages.value = list + errorUiMessage(message)
    }

    /** 构造错误提示 UiMessage（红色气泡，独占一条）。 */
    private fun errorUiMessage(message: String): UiMessage =
        UiMessage("agent-error-${System.nanoTime()}", Role.ERROR, message, timeMillis = System.currentTimeMillis())

    private fun appendToolResult(msgs: List<UiMessage>, ev: ConversationEvent.ToolResult): List<UiMessage> {
        val lastAsstIdx = msgs.indexOfLast { it.role == Role.ASSISTANT && it.toolCalls.isNotEmpty() }
        if (lastAsstIdx < 0) {
            // 兜底：无助手消息可合并 → 独立工具结果消息（带宿主单行摘要）
            return msgs + UiMessage("tool-${System.nanoTime()}", Role.TOOL, ev.output, toolError = ev.isError, toolViewTitle = ev.viewTitle)
        }
        val list = msgs.toMutableList()
        val m = list[lastAsstIdx]
        list[lastAsstIdx] = m.copy(
            toolResults = m.toolResults + ToolResultDisplay(ev.output, ev.viewTitle),
            toolError = m.toolError || ev.isError,
        )
        return list
    }

    /**
     * 按 key upsert 一条消息：存在同 key 则原位替换（历史+实时重叠/重复回显去重），否则追加。
     * 用户消息额外处理"乐观本地消息(local-user-*)"：服务端回显同文本时原位替换，避免两条。
     */
    private fun upsertMessage(msg: UiMessage) {
        val list = _messages.value
        // 乐观本地用户消息：key 前缀 local-user- 且同文本 → 用服务端消息原位替换
        if (msg.role == Role.USER) {
            val localIdx = list.indexOfLast { it.key.startsWith("local-user-") && it.text == msg.text }
            if (localIdx >= 0) {
                _messages.value = list.toMutableList().apply { this[localIdx] = msg }
                return
            }
        }
        // 同 key 已存在 → 原位替换；否则追加
        val idx = list.indexOfFirst { it.key == msg.key }
        _messages.value = if (idx >= 0) list.toMutableList().apply { this[idx] = msg } else list + msg
    }

    /**
     * 流式聚合（节流版，修复 P0 卡死）：
     * 每次受到 assistant/chunk 增量不再立即全量重建消息列表（高频 chunk 会压满主线程），
     * 而是追加到内存 StringBuilder（O(1)），由 `flushStream` 协程每 STREAM_FLUSH_MS 合并刷新一次
     * `_messages.value`，把列表更新频率锁死在一个常数（约 12 次/秒），彻底消除"每 chunk 刷全列表"。
     */
    private val streamTextBuf = StringBuilder()
    private val streamThinkBuf = StringBuilder()
    private var streamPending = false
    // 压测量化：记录收到 chunk 数与实际 flush 次数，验证节流把高频 chunk 合并成低频列表更新
    private var streamChunkCount = 0L
    private var streamFlushCount = 0L

    private fun aggregateStreaming(delta: ConversationEvent.AssistantChunkDelta) {
        streamChunkCount++
        delta.textDelta?.let { streamTextBuf.append(it) }
        delta.thinkingDelta?.let { streamThinkBuf.append(it) }
        streamPending = true
    }

    /** 把字节缓冲的流式文本合并到 STREAMING_KEY 消息（一次重建）。残留 buffer 随调用清空。 */
    private fun flushStream() {
        if (!streamPending) return
        streamPending = false
        val textDelta = streamTextBuf.toString()
        val thinkDelta = streamThinkBuf.toString()
        streamTextBuf.setLength(0); streamThinkBuf.setLength(0)
        if (textDelta.isEmpty() && thinkDelta.isEmpty()) return
        streamFlushCount++
        // 节流生效量化：chunk 数 / flush 次数 应远大于 1（高频 chunk 被合并）；仅 debug 打点，R8 移除
        if (BuildConfig.DEBUG && streamFlushCount % 10L == 0L) {
            Log.d(TAG, "stream throttle: chunks=$streamChunkCount flush=$streamFlushCount " +
                "merged=${if (streamFlushCount > 0) streamChunkCount / streamFlushCount else 0} charsPerFlush=${textDelta.length}")
        }
        val existing = _messages.value.lastOrNull { it.key == STREAMING_KEY }
        val newText = (existing?.text ?: "") + textDelta
        val newThinking = (existing?.reasoning ?: "") + thinkDelta
        val msg = existing?.copy(text = newText, reasoning = newThinking)
            ?: UiMessage(STREAMING_KEY, Role.ASSISTANT, newText, newThinking)
        // 替换流式消息位置，保持其在列表靠后（流式未完成的一条）
        _messages.value = _messages.value.filter { it.key != STREAMING_KEY } + msg
    }

    /** 节流刷新的驱动协程：每 STREAM_FLUSH_MS 触发一次合并（如无待刷新立即跳过）。 */
    private fun startStreamFlusher() {
        viewModelScope.launch {
            while (true) {
                delay(STREAM_FLUSH_MS)
                flushStream()
            }
        }
    }

    fun send(text: String) {
        if (text.isBlank()) return
        // 发送前的运行态：true = 当前回合正在跑，按 dsh 新版契约（session/prompt，mode=queue，
        // 与官方 WebUI 发消息一致）这条消息会**排入队列**、等回合结束后自动执行。
        // 记录它以在受理成功后给用户明确反馈（否则用户会误判"没发送"）。
        val wasRunning = _running.value
        _running.value = true
        // 乐观插入用户消息（历史/实时不重复），key 用本地时间戳保证唯一
        _messages.value = _messages.value + UiMessage(
            key = "local-user-${System.currentTimeMillis()}",
            role = Role.USER,
            text = text,
            timeMillis = System.currentTimeMillis(),
        )
        viewModelScope.launch {
            when (val r = DshRepository.apiClient.sessionPrompt(sessionId, text)) {
                is ApiResult.Ok -> {
                    // 发送受理即标记"本机已发过消息"（本地事实）：列表立即豁免 blank 隐藏——
                    // 不等宿主 turn/start（数秒延迟）与推送帧（慢链路可能丢），返回列表即刻可见
                    DshRepository.markSessionPrompted(sessionId)
                    if (wasRunning) {
                        // 排队反馈（手机实测报障修复）：消息已受理但会等当前回合结束才执行
                        _actionError.value = "已加入队列：当前回合运行中，这条消息会在回合结束后自动发送。"
                    }
                }
                // 发送失败必须可见（审计 Top3-①：静默失败=用户以为发出去了，agent 永远不回）：
                // 打点失败码 + Toast 提示；running 复位。乐观消息保留（内容没丢，可重发）。
                is ApiResult.BizError -> {
                    Log.w(TAG, "prompt biz error: ${r.code} / ${r.message}")
                    _actionError.value = "发送失败（${r.code}），请重试。"
                    _running.value = false
                }
                is ApiResult.NetError -> {
                    Log.w(TAG, "prompt net error: ${r.throwable.message}")
                    _actionError.value = "发送失败（网络），请重试。"
                    _running.value = false
                }
            }
        }
    }

    fun stop() {
        // 先登记抑制窗（通知状态机），再 cancel（§5.2 onLocalCancel）
        DshRepository.notifyLocalCancel(sessionId)
        _running.value = false
        viewModelScope.launch {
            DshRepository.apiClient.sessionCancel(sessionId)
        }
    }

    /** ViewModel 销毁：清理流式节流缓冲（避免残留占位）。 */
    override fun onCleared() {
        streamTextBuf.setLength(0)
        streamThinkBuf.setLength(0)
        streamPending = false
        super.onCleared()
    }

    /** Converter：ConversationEvent → 可渲染 UiMessage（非消息类事件返回 null）。 */
    private fun ConversationEvent.toUiMessage(): UiMessage? = when (this) {
        is ConversationEvent.UserMessage ->
            UiMessage(key, Role.USER, text, timeMillis = timeMillis)
        is ConversationEvent.AssistantMessage ->
            UiMessage(key, Role.ASSISTANT, text, reasoning, toolCalls, timeMillis = timeMillis)
        is ConversationEvent.ToolResult ->
            UiMessage(key, Role.TOOL, output, toolError = isError, timeMillis = timeMillis, toolViewTitle = viewTitle)
        is ConversationEvent.ContextInjection ->
            UiMessage(key, Role.CONTEXT, text, timeMillis = timeMillis,
                contextRole = role, contextLabel = label)
        else -> null   // TurnStart/TurnEnd/AssistantChunkDelta/Unknown 不渲染成独立消息
    }

    companion object {
        private const val TAG = "ConversationVM"

        /** 流式消息的稳定 key（同 turn 聚合；助手消息 settled 时替换）。 */
        internal const val STREAMING_KEY = "__streaming__"   // UI 需识别流式占位（流式期间纯文本渲染防闪烁）

        /** 流式节流刷新间隔（毫秒）：把高频 chunk 合并到低频 _messages.value 更新，锁死列表重建频率。 */
        private const val STREAM_FLUSH_MS = 80L

        /** 会话参数工厂（Activity 以 intent 的 sessionId 构造）。 */
        fun factory(sessionId: String) = viewModelFactory {
            initializer { ConversationViewModel(sessionId) }
        }
    }
}
