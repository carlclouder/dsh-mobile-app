package dev.dshmobile.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import dev.dshmobile.DshApplication
import dev.dshmobile.DshRepository
import dev.dshmobile.MainActivity
import dev.dshmobile.R
import dev.dshmobile.model.ApiResult
import dev.dshmobile.model.FrameParser
import dev.dshmobile.model.HostFrame
import dev.dshmobile.model.MuxFrame
import dev.dshmobile.notify.NotificationHelper
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.coroutines.coroutineContext

/**
 * 前台监听服务（设计 §5.2 完整实现）：specialUse 类型 + 双 WebSocket + 断线退避重连 +
 * baseline 对账 + 网络切换立即重连 + 回合完成/审批/提问三类通知。
 *
 * 结构：
 * - 每条流一个 [runEventStream] 协程：开 socket → 挂起等关闭 → 退避重连（1s→60s）
 * - 状态机（纯 Kotlin）负责通知决策；本服务执行 Command（§5.2 注释里的"通知指令流"）
 * - 换址检测：socket 回调对比世代号（DshApiClient P1-4 修复），跨代 socket 主动废弃
 * - onTimeout 优雅降级兜底（specialUse 现行无时限，防未来平台收紧）
 */
class EventStreamService : android.app.Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 状态机互斥锁：onMessage 回调来自 OkHttp 线程，baseline 来自服务协程。 */
    private val machineLock = Any()

    private val stateMachine = NotificationStateMachine()

    /** session/event 帧 seq 水位去重器（修复"流式叠词"：重复投递的帧不进 UI 流，见类注释）。 */
    private val sessionEventDeduper = SessionEventDeduper()
    private lateinit var notifier: NotificationHelper
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var hostJob: Job? = null
    private var muxJob: Job? = null

    /** 轮询协程（新版 dsh 无 WS 事件流，以周期全量对账驱动状态；见 startStreams 注释）。 */
    private var pollJob: Job? = null

    /** 双流开启状态跟踪（连接状态条数据源）。 */
    @Volatile private var hostOpened = false
    @Volatile private var muxOpened = false
    /** 连续失败计数（原子读改写，终审 P2-4；onOpen 清零）。 */
    private val consecutiveFailures = java.util.concurrent.atomic.AtomicInteger(0)

    // -------------------------------------------------------------------
    // 生命周期
    // -------------------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifier = NotificationHelper(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground("DSH 监听中", "正在监听远端会话状态")
        acquireWakeLock()
        registerNetworkCallback()
        // 启动期恢复设置中的地址（P1-4：换址路径统一走 DshRepository.applyBaseUrl）
        serviceScope.launch {
            runCatching { DshRepository.restoreBaseUrlFromSettings() }
        }
        // P0-1 修复：两个永久 collect 必须各自独立协程——collectToggles 永不返回，
        // 若串行排在 collectLocalCancels 之前，cancel 抑制窗登记将永不可达（终审 P0-1）。
        // 收集器带 Job 跟踪：onStartCommand 重复到达时先取消旧收集器再启新的（防堆积，终审 P2-3）。
        collectorsJob?.cancel()
        collectorsJob = serviceScope.launch {
            launch { collectToggles() }
            launch { collectLocalCancels() }
            launch { collectManualRefresh() }
            launch { collectStreamKicks() }
        }
        startStreams()
        return START_STICKY
    }

    /** 收集器协程组（开关热更新 + 本地取消抑制窗登记 + 手动刷新对账）。 */
    private var collectorsJob: Job? = null

    /** 下拉刷新（§5.4）：请求到达即重跑 baseline 对账（session.list 为权威全量）。 */
    private suspend fun collectManualRefresh() {
        DshRepository.manualRefreshRequests.collect { runBaseline() }
    }

    /**
     * "立即重连"踢（修复"App 先于宿主启动需重启 App"）：收到即跳过退避重开两条流。
     * 幂等：已连接时重开也无害（会经历一次断开-立即重连，帧序由 seq 去重器兜底）。
     */
    private suspend fun collectStreamKicks() {
        DshRepository.streamKickRequests.collect {
            android.util.Log.i(TAG, "stream kick: immediate reconnect requested")
            restartStreams("foreground-kick")
        }
    }

    /** 对话页停止按钮的抑制窗登记（§5.2 onLocalCancel：cancel 产生的 running:false 不触发完成通知）。 */
    private suspend fun collectLocalCancels() {
        DshRepository.localCancelEvents.collect { sessionId ->
            synchronized(machineLock) { stateMachine.onLocalCancel(sessionId) }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        unregisterNetworkCallback()
        releaseWakeLock()
        // 终审 P1-3：只轻关活动流，不关 WS 执行器——服务同进程重启（START_STICKY/用户点恢复）
        // 时若执行器已 shutdown，openEventStream 抛 RejectedExecutionException 且未被捕获 → 进程崩。
        // 全量 shutdown 留给进程终止（DshApiClient 随单例自然消亡）。
        runCatching { DshRepository.apiClient.closeEventSockets() }
        DshRepository.publishConnection(DshRepository.ConnectionState.UNKNOWN)
        super.onDestroy()
    }

    /**
     * specialUse 前台服务超时兜底（Android 15 起 onTimeout 回调）：当前平台对 specialUse
     * 无时限，此为实现保险——停流、常驻通知改为"已暂停"、自停，不抛 Fatal Exception。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        serviceScope.cancel()
        hostJob = null; muxJob = null
        startInForeground("监听已暂停", "点按恢复（系统限制前台服务运行时长）")
        releaseWakeLock()
        stopSelf(startId)
    }

    // -------------------------------------------------------------------
    // 前台通知 / 权限资源
    // -------------------------------------------------------------------

    private fun startInForeground(title: String, text: String) {
        val tapIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, DshApplication.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NotificationHelper.SERVICE_NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NotificationHelper.SERVICE_NOTIFICATION_ID, notification)
        }
    }

    /** 整夜挂机需要 CPU 不睡（WS 保活）；释放于 onDestroy/onTimeout。 */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dshmobile:eventstream").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.release() }
        wakeLock = null
    }

    /** 网络切换（WiFi↔移动网/Tailscale 重建）：立即断流重连，不等 ping 超时（设计 §5.2 P2）。
     *  能力回调带边沿检测（终审 P2-2）：VALIDATED 从无到有才触发，能力位微变不拆建 WS。 */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        var lastValidated = false
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                restartStreams("network-available")
            }
            override fun onLost(network: Network) {
                DshRepository.publishConnection(DshRepository.ConnectionState.RECONNECTING)
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (validated && !lastValidated) restartStreams("network-validated")
                lastValidated = validated
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        networkCallback = callback
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { cb ->
            runCatching {
                (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(cb)
            }
        }
        networkCallback = null
    }

    // -------------------------------------------------------------------
    // 流回路
    // -------------------------------------------------------------------

    /**
     * 启动状态同步。
     *
     * 新版 dsh(≥0.1.2-rc.1) 移除了 /api/events.host|events.mux 两条 WebSocket 事件流
     * （2026-09-16 实测：带有效会话 Cookie 仍 404/升级被断，官方 WebUI 自身改为 HTTP 轮询），
     * 因此以**周期轮询 session.list 全量对账**替代两条 WS：状态机 / 通知（回合完成、审批、
     * 提问）/ 会话列表 / 工作区 全部由 runBaseline() 驱动（与"下拉刷新"同一条权威链路），
     * 旧版与新版宿主都能用（session.list 两边都存在）。WS 相关代码保留备查，不再启动。
     */
    private fun startStreams() {
        pollJob?.cancel()
        pollJob = serviceScope.launch { runPollingLoop() }
    }

    /** 轮询主循环：全量对账 + 连接状态发布（失败连续 3 次判不可达）。 */
    private suspend fun runPollingLoop() {
        while (true) {
            val ok = runBaseline()
            publishPollingState(ok)
            delay(POLL_INTERVAL_MS)
        }
    }

    /** 轮询连接状态发布：成功即已连接并清零失败计数。 */
    private fun publishPollingState(ok: Boolean) {
        val state = when {
            ok -> {
                consecutiveFailures.set(0)
                DshRepository.ConnectionState.CONNECTED
            }
            consecutiveFailures.incrementAndGet() >= 3 -> DshRepository.ConnectionState.UNREACHABLE
            else -> DshRepository.ConnectionState.RECONNECTING
        }
        DshRepository.publishConnection(state)
    }

    private fun restartStreams(reason: String) {
        // 轮询模式无需强制重开连接：下一轮询周期（≤POLL_INTERVAL_MS）自然重跑，仅记日志便于排查
        android.util.Log.i(TAG, "poll kick: $reason (next tick within ${POLL_INTERVAL_MS}ms)")
    }

    private enum class StreamKind(val path: String) {
        HOST("/api/events.host"), MUX("/api/events.mux");
    }

    /**
     * 单条流的永久回路：开 socket → 等待关闭 → 退避后重开。
     * 世代号：回调与开流时刻比对，跨代（换址残留）socket 主动 cancel 促其快速出清。
     */
    private suspend fun runEventStream(kind: StreamKind) {
        var backoffMs = 1_000L
        while (coroutineContext.isActive) {
            val generationAtOpen = DshRepository.apiClient.generation()
            val closed = CompletableDeferred<Unit>()
            val openedFlag = java.util.concurrent.atomic.AtomicBoolean(false)
            val listener = makeListener(kind, generationAtOpen, closed, openedFlag)
            try {
                DshRepository.apiClient.openEventStream(kind.path, listener)
            } catch (e: IllegalStateException) {
                // 基址非法（P1-4 路径）：不可达状态 + 长退避
                DshRepository.publishConnection(DshRepository.ConnectionState.UNREACHABLE)
                delay(60_000L)
                continue
            }
            closed.await()   // 挂起至本条 socket 关闭（任何原因）
            if (kind == StreamKind.HOST) hostOpened = false else muxOpened = false
            updateConnectionState(openedFlag.get())
            // 成功连过 → 退避归位（修复"App 先于宿主启动：退避爬到上限后，宿主恢复仍要等满
            // 长间隔，观感等于死锁、需重启 App"）；上限压到 15s：宿主恢复后最迟 15 秒自动重连
            if (openedFlag.get()) backoffMs = 1_000L
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(15_000L)
        }
    }

    /** per-socket 监听器工厂：onOpen 触发 baseline；onMessage 分发帧；关闭/失败完成 deferred。 */
    private fun makeListener(
        kind: StreamKind,
        generationAtOpen: Long,
        closed: CompletableDeferred<Unit>,
        openedFlag: java.util.concurrent.atomic.AtomicBoolean,
    ): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            openedFlag.set(true)
            consecutiveFailures.set(0)
            if (kind == StreamKind.HOST) hostOpened = true else muxOpened = true
            updateConnectionState(openedSuccessfully = true)
            // 每次成功（重）连都对账：冷启动静默、断连窗口完成的回合补发（状态机规则）
            serviceScope.launch { runBaseline() }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // 跨代 socket（换址残留）：主动废弃，等新回路接管
            if (DshRepository.apiClient.generation() != generationAtOpen) {
                webSocket.cancel()
                return
            }
            handleRawFrame(kind, text)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            android.util.Log.i(TAG, "stream closed ${kind.path} code=$code reason=$reason")
            closed.complete(Unit)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // 失败原因日志：断线退避排查与连接状态判定的关键观测点
            android.util.Log.w(TAG, "stream failed ${kind.path} (gen=$generationAtOpen): ${t.javaClass.simpleName}: ${t.message} resp=${response?.code}")
            closed.complete(Unit)
        }
    }

    // -------------------------------------------------------------------
    // 帧处理
    // -------------------------------------------------------------------

    private fun handleRawFrame(kind: StreamKind, text: String) {
        val envelope = FrameParser.parseEnvelope(text) ?: run {
            android.util.Log.w(TAG, "dropped malformed envelope on ${kind.path}")
            return
        }
        // 锁内完成"状态变更 + 指令生成 + 指令执行 + 快照发布"四步（终审 P1-1/P2-5）：
        // 快照拷贝与指令执行若在锁外，双流并发下会 CME / 跨流乱序（baseline 的 Cancel
        // 可能先于 host 流更早的 Show 执行）。指令执行（notify/cancel）均为非重入快速调用，
        // 锁内执行无死锁风险（NotificationHelper 不回调本服务）。
        synchronized(machineLock) {
            when (kind) {
                StreamKind.HOST -> {
                    val frame = FrameParser.parseHostFrame(envelope.payload) ?: return@synchronized
                    (frame as? HostFrame.WorkspaceChanged)?.let { upsertWorkspace(it.workspace) }
                    // 需求 2.9.3：归档集变化 → 注入状态机（快照排除归档，列表隐藏/不通知）
                    (frame as? HostFrame.ArchivedSessionsChanged)?.let {
                        stateMachine.setArchivedSessionIds(it.archivedSessionIds)
                    }
                    // 模型/代理调用错误（额度用完/API失败）：发布为会话内错误事件供对话页显示，
                    // 状态机仍不通知（避免与错误提示叠加）
                    (frame as? HostFrame.AgentError)?.let { err ->
                        DshRepository.publishSessionEvent(
                            err.sessionId,
                            dev.dshmobile.model.ConversationEvent.AgentError(
                                key = "agent-error-${System.nanoTime()}", message = err.message,
                            ),
                        )
                    }
                    executeCommands(stateMachine.onHostFrame(frame))
                    publishSnapshot()
                }
                StreamKind.MUX -> {
                    val frame = FrameParser.parseMuxFrame(envelope.payload) ?: return@synchronized
                    (frame as? MuxFrame.StreamError)?.let {
                        android.util.Log.w(TAG, "stream/error on mux: ${it.error.message}; reconnecting")
                        // §5.2：流错误触发该流重连——cancel 本 socket，runEventStream 的
                        // closed.await() 返回后退避重开
                        // （socket 引用不在此处，靠 closeEventSockets 全量轻关代价可接受）
                        runCatching { DshRepository.apiClient.closeEventSockets() }
                    }
                    // M5 原生对话页：session/event 帧解析并发布给对话 UI（与通知逻辑并行）。
                    // seq 水位去重（修复"流式叠词"）：传输/重连层偶发重复投递同一帧时，文本追加类
                    // 事件（chunk/工具结果）会重复渲染；宿主 seq 严格递增，重复帧在此丢弃。
                    (frame as? MuxFrame.SessionEvent)?.let { sessEvent ->
                        val evt = sessEvent.event
                        val type = (evt["type"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                        val seq = (evt["seq"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toLongOrNull()
                            ?: 0L
                        if (!sessionEventDeduper.admit(sessEvent.sessionId, seq)) return@let
                        val time = (evt["time"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toLongOrNull()
                            ?: 0L
                        val data = (evt["data"] as? kotlinx.serialization.json.JsonObject) ?: kotlinx.serialization.json.JsonObject(emptyMap())
                        // 宿主逐工具单行摘要（view.view.title，WebUI 紧凑渲染对齐）
                        val viewTitle = ((sessEvent.view?.get("view") as? kotlinx.serialization.json.JsonObject)
                            ?.get("title") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                        DshRepository.publishSessionEvent(sessEvent.sessionId, dev.dshmobile.model.ConversationEvent.parse(type ?: "", seq, data, time, viewTitle))
                    }
                    // 需求：排队消息流（编辑/删除）——session/queue 帧解析发布
                    (frame as? MuxFrame.SessionQueue)?.let { q ->
                        val items = q.items.mapNotNull { el ->
                            val obj = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                            val id = (obj["id"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                            val placement = (obj["placement"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: ""
                            val msg = obj["message"] as? kotlinx.serialization.json.JsonObject
                            val text = (msg?.get("content") as? kotlinx.serialization.json.JsonArray)
                                ?.mapNotNull { b -> (b as? kotlinx.serialization.json.JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
                                ?.joinToString("") ?: ""
                            dev.dshmobile.model.QueueItem(id, text, placement)
                        }
                        DshRepository.publishSessionQueue(q.sessionId, items)
                    }
                    // 需求：会话统计（底部信息栏）——sessionStats 投影解析发布
                    (frame as? MuxFrame.SessionProjection)?.let { proj ->
                        if (proj.key == "sessionStats") {
                            val v = proj.value as? kotlinx.serialization.json.JsonObject ?: return@let
                            val stats = try {
                                dev.dshmobile.model.DshJson.decodeFromJsonElement(
                                    dev.dshmobile.model.SessionStats.serializer(), v)
                            } catch (e: Exception) { return@let }
                            DshRepository.publishSessionStats(proj.sessionId, stats)
                        }
                    }
                    executeCommands(stateMachine.onMuxFrame(envelope.rpcId, frame))
                    publishSnapshot()
                }
            }
        }
    }

    /** 工作区 upsert（终审 P1-2：单帧不得整体覆盖全量列表，按 workspaceId 合并）。 */
    private fun upsertWorkspace(changed: dev.dshmobile.model.WorkspaceView) {
        val current = DshRepository.workspaces.value
        val merged = current.filter { it.workspaceId != changed.workspaceId } + changed
        DshRepository.publishWorkspaces(merged.sortedBy { it.workspaceId })
    }

    // -------------------------------------------------------------------
    // baseline 对账
    // -------------------------------------------------------------------

    private suspend fun runBaseline(): Boolean {
        var sessionListOk = false
        when (val result = DshRepository.apiClient.sessionList()) {
            is ApiResult.Ok -> {
                sessionListOk = true
                // 与帧处理同锁：对账合并 + 补发指令 + 快照发布原子化（终审 P1-1/P2-5）
                synchronized(machineLock) {
                    executeCommands(stateMachine.onBaseline(result.value))
                    publishSnapshot()
                }
            }
            is ApiResult.BizError ->
                android.util.Log.w(TAG, "baseline session.list biz error: ${result.code}")
            is ApiResult.NetError ->
                android.util.Log.w(TAG, "baseline session.list net error: ${result.throwable.message}")
        }
        // 工作区列表（M4 新建会话数据源 + 需求 2.9.3 归档集注入；失败不阻塞会话链路）
        when (val result = DshRepository.apiClient.workspaceList()) {
            is ApiResult.Ok -> {
                DshRepository.publishWorkspaces(result.value.items)
                // 归档集注入状态机（快照排除归档 → 列表隐藏/不通知）
                synchronized(machineLock) {
                    stateMachine.setArchivedSessionIds(result.value.archivedSessionIds)
                    publishSnapshot()
                }
            }
            else -> Unit
        }
        return sessionListOk
    }

    // -------------------------------------------------------------------
    // 指令执行与状态发布
    // -------------------------------------------------------------------

    private fun executeCommands(commands: List<NotificationStateMachine.Command>) {
        for (command in commands) {
            when (command) {
                is NotificationStateMachine.Command.ShowTurnComplete ->
                    notifier.notifyTurnComplete(command.sessionId, command.displayTitle)
                is NotificationStateMachine.Command.CancelTurnComplete ->
                    notifier.cancelTurnComplete(command.sessionId)
                is NotificationStateMachine.Command.ShowApproval ->
                    notifier.notifyApproval(command.info)
                is NotificationStateMachine.Command.CancelApproval ->
                    notifier.cancelApproval(command.approvalId)
                is NotificationStateMachine.Command.ShowQuestion ->
                    notifier.notifyQuestion(command.info)
                is NotificationStateMachine.Command.CancelQuestion ->
                    notifier.cancelQuestion(command.questionRpcId)
            }
        }
        // 同步挂起审批/提问到仓库（对话页内联卡片数据源）
        DshRepository.setPendingApprovals(stateMachine.snapshotApprovals())
        DshRepository.setPendingQuestions(stateMachine.snapshotQuestions())
    }

    private fun publishSnapshot() {
        DshRepository.publishSessions(stateMachine.sessionSnapshot())
    }

    private fun updateConnectionState(openedSuccessfully: Boolean) {
        val state = when {
            hostOpened && muxOpened -> DshRepository.ConnectionState.CONNECTED
            !openedSuccessfully && consecutiveFailures.incrementAndGet() >= 3 ->
                DshRepository.ConnectionState.UNREACHABLE
            else -> DshRepository.ConnectionState.RECONNECTING
        }
        DshRepository.publishConnection(state)
    }

    /** 通知开关热更新（设置页变更即时生效，无需重启服务）。 */
    private suspend fun collectToggles() {
        DshRepository.settingsStore.togglesFlow.collect { toggles ->
            notifier.applyToggles(toggles)
        }
    }

    companion object {
        private const val TAG = "EventStreamService"

        /** 全量对账轮询间隔（毫秒）：3s 兼顾实时观感与请求开销（新版无 WS 推送的替代方案）。 */
        private const val POLL_INTERVAL_MS = 3_000L
    }
}
