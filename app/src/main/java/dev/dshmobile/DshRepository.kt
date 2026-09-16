package dev.dshmobile

import android.content.Context
import dev.dshmobile.data.SettingsStore
import dev.dshmobile.model.WorkspaceView
import dev.dshmobile.network.DshApiClient
import dev.dshmobile.service.NotificationStateMachine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 单例状态源（设计 §4/§6）：EventStreamService 写入，UI（M4）读取。
 *
 * 持有：
 * - DshApiClient 实例（服务/接收器/设置页共用，换址走 [applyBaseUrl] 统一入口）
 * - 会话快照流（状态机快照的拷贝）
 * - 工作区列表流（新建会话底弹数据源）
 * - 连接状态流（列表页顶部状态条）
 *
 * 初始化：DshApplication.onCreate 调用 [initialize]（进程级单次）。
 */
object DshRepository {

    /** 连接状态（列表页状态条三态 + 初始未知）。 */
    enum class ConnectionState { UNKNOWN, CONNECTED, RECONNECTING, UNREACHABLE }

    lateinit var apiClient: DshApiClient
        private set
    lateinit var settingsStore: SettingsStore
        private set

    private val _connectionState = MutableStateFlow(ConnectionState.UNKNOWN)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** 本地取消事件（对话页停止按钮 → EventStreamService 收集登记抑制窗，§5.2 onLocalCancel）。 */
    private val _localCancelEvents = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    val localCancelEvents: kotlinx.coroutines.flow.SharedFlow<String> = _localCancelEvents

    fun notifyLocalCancel(sessionId: String) {
        _localCancelEvents.tryEmit(sessionId)
    }

    /** 手动刷新请求（§5.4 下拉刷新：平时靠推送，刷新走 session.list 重拉对账）。 */
    private val _manualRefreshRequests =
        kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val manualRefreshRequests: kotlinx.coroutines.flow.SharedFlow<Unit> = _manualRefreshRequests

    fun requestManualRefresh() {
        _manualRefreshRequests.tryEmit(Unit)
    }

    /**
     * "立即重连"请求（修复"App 先于宿主启动，需重启 App 才恢复连接"）：
     * 回前台且未连接时由 UI 触发，服务收到后跳过退避立即重开事件流。
     */
    private val _streamKickRequests =
        kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val streamKickRequests: kotlinx.coroutines.flow.SharedFlow<Unit> = _streamKickRequests.asSharedFlow()

    fun requestStreamKick() {
        _streamKickRequests.tryEmit(Unit)
    }

    /**
     * 会话对话事件（M5 原生对话页实时渲染）：EventStreamService 把 mux 流 session/event 帧
     * 解析后按会话发布；ConversationViewModel 只收集当前打开会话的事件。
     * extraBufferCapacity 兜底瞬时高峰（事件量大但不丢关键消息，极端下让 shared flow 缓冲）。
     */
    private val _sessionEvents =
        kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, dev.dshmobile.model.ConversationEvent>>(
            extraBufferCapacity = 256,
        )
    val sessionEvents: kotlinx.coroutines.flow.SharedFlow<Pair<String, dev.dshmobile.model.ConversationEvent>> =
        _sessionEvents

    fun publishSessionEvent(sessionId: String, event: dev.dshmobile.model.ConversationEvent) {
        _sessionEvents.tryEmit(sessionId to event)
    }

    /**
     * 排队消息快照镜像（按会话）：StateFlow 而非 SharedFlow——无 replay 的 SharedFlow 在对话页
     * 重建时收不到历史帧，排队区初始恒空，插话/编辑/删除的回显收敛也会失去初始依据。
     * 改 StateFlow 后进页面立即镜像最新值（对齐 WebUI 打开页面即有全量队列的语义）。
     * EventStreamService 每收到 session/queue 帧全量覆盖对应会话；update{} 做 CAS 原子合并。
     * 生命周期：条目按会话累积、随进程消亡（单机场景条目极小；会话清理功能落地时在此处挂接移除）。
     */
    private val _sessionQueue = MutableStateFlow<Map<String, List<dev.dshmobile.model.QueueItem>>>(emptyMap())
    val sessionQueue: StateFlow<Map<String, List<dev.dshmobile.model.QueueItem>>> = _sessionQueue.asStateFlow()

    fun publishSessionQueue(sessionId: String, items: List<dev.dshmobile.model.QueueItem>) {
        _sessionQueue.update { current -> current + (sessionId to items) }
    }

    /** 会话统计流（需求：底部信息栏——轮/步/耗时）。服务解析 sessionStats 投影发布。 */
    private val _sessionStats =
        kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, dev.dshmobile.model.SessionStats>>(extraBufferCapacity = 8)
    val sessionStats: kotlinx.coroutines.flow.SharedFlow<Pair<String, dev.dshmobile.model.SessionStats>> = _sessionStats

    fun publishSessionStats(sessionId: String, stats: dev.dshmobile.model.SessionStats) {
        _sessionStats.tryEmit(sessionId to stats)
    }

    /** 挂起审批（按会话分组）：服务同步状态机快照；对话页内联审批卡。 */
    private val _pendingApprovals =
        MutableStateFlow<Map<String, List<NotificationStateMachine.ApprovalInfo>>>(emptyMap())
    val pendingApprovals: StateFlow<Map<String, List<NotificationStateMachine.ApprovalInfo>>> =
        _pendingApprovals.asStateFlow()

    fun setPendingApprovals(map: Map<String, List<NotificationStateMachine.ApprovalInfo>>) {
        _pendingApprovals.value = map
    }

    /** 挂起提问（按会话分组）：服务同步状态机快照；对话页内联提问卡。 */
    private val _pendingQuestions =
        MutableStateFlow<Map<String, List<NotificationStateMachine.QuestionInfo>>>(emptyMap())
    val pendingQuestions: StateFlow<Map<String, List<NotificationStateMachine.QuestionInfo>>> =
        _pendingQuestions.asStateFlow()

    fun setPendingQuestions(map: Map<String, List<NotificationStateMachine.QuestionInfo>>) {
        _pendingQuestions.value = map
    }

    /**
     * 本机已成功发出首条消息的会话集合（修复"发送后返回列表不立即显示"）：
     * 发送 RPC 受理即标记——这是客户端本地事实，不受宿主 agent 启动延迟（turn/start
     * 落表需数秒）与慢链路丢帧影响。列表对"已标记的 blank 会话"豁免隐藏，
     * 宿主随后转正（running 帧/baseline）即回归常规路径。进程内存活，重启后由宿主状态接管。
     */
    private val _promptMarkedSessions = MutableStateFlow<Set<String>>(emptySet())
    val promptMarkedSessions: StateFlow<Set<String>> = _promptMarkedSessions.asStateFlow()

    fun markSessionPrompted(sessionId: String) {
        _promptMarkedSessions.value = _promptMarkedSessions.value + sessionId
    }

    private val _sessions = MutableStateFlow<Map<String, NotificationStateMachine.SessionState>>(emptyMap())
    val sessions: StateFlow<Map<String, NotificationStateMachine.SessionState>> = _sessions.asStateFlow()

    private val _workspaces = MutableStateFlow<List<WorkspaceView>>(emptyList())
    val workspaces: StateFlow<List<WorkspaceView>> = _workspaces.asStateFlow()

    @Volatile
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            settingsStore = SettingsStore(context.applicationContext)
            apiClient = DshApiClient(SettingsStore.DEFAULT_BASE_URL)
            // 新版 dsh 令牌认证适配：注册 Cookie 持久化（会话 Cookie 30 天内跨 App 重启复用）
            dev.dshmobile.network.DshAuthSession.attach(context.applicationContext)
            initialized = true
        }
    }

    /** 启动期恢复已存地址（Application 初始化后、服务启动前调用）。 */
    suspend fun restoreBaseUrlFromSettings() {
        ensureInitialized()
        apiClient.updateBaseUrl(settingsStore.baseUrlOnce())
    }

    /** 设置页改址统一入口：存储 + 客户端同步（换址会关闭存量事件流，见 DshApiClient P1-4 修复）。 */
    suspend fun applyBaseUrl(url: String) {
        ensureInitialized()
        settingsStore.setBaseUrl(url)
        apiClient.updateBaseUrl(url.trim().ifBlank { SettingsStore.DEFAULT_BASE_URL })
    }

    // ---- 服务侧写入（UI 只读） ----

    fun publishConnection(state: ConnectionState) { _connectionState.value = state }

    fun publishSessions(snapshot: Map<String, NotificationStateMachine.SessionState>) {
        _sessions.value = snapshot
    }

    fun publishWorkspaces(list: List<WorkspaceView>) { _workspaces.value = list }

    private fun ensureInitialized() {
        check(initialized) { "DshRepository.initialize must run first (DshApplication.onCreate)" }
    }
}
