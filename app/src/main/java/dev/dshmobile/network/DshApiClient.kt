package dev.dshmobile.network

import dev.dshmobile.BuildConfig
import dev.dshmobile.model.ApiResult
import dev.dshmobile.model.ConversationEvent
import dev.dshmobile.model.DshJson
import dev.dshmobile.model.QuestionAnswerItem
import dev.dshmobile.model.RespondReceipt
import dev.dshmobile.model.SessionListValue
import dev.dshmobile.model.SessionSummary
import dev.dshmobile.model.WorkspaceListValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * DSH 单次调用客户端（设计 §5.1）。
 *
 * 线上契约（源码钉死，见 model/Models.kt 头注释）：
 * - POST /api/<method>，头 Content-Type: application/json（缺失即 415），
 *   体 {"type":"client-request","rpcId","method","payload"}；
 *   响应 {"type":"server-response","rpcId","result":{"ok":true,"value"?}|{"ok":false,"error"}}。
 * - POST /api/respond 体为 client-response 形态，回执三态见 [RespondReceipt]。
 * - TLS：*.ts.net 证书由 Let's Encrypt 公共 CA 签发，系统信任链零配置。
 */
class DshApiClient(baseUrl: String) {

    /**
     * 可变基址：设置页改地址后调用 [updateBaseUrl]，单机场景无并发竞争，仍加锁保秩。
     * 新版 dsh(≥0.1.2-rc.1) 适配：地址允许携带一次性令牌（`?token=`，用户从新版启动
     * 输出复制的完整链接）——构造/换址时经 [DshAuthSession.splitBaseUrl] 拆出 base 与
     * token 并登记，实际网络层由 [DshAuthSession.authInterceptor] 自动完成令牌换会话
     * Cookie 与 401 自愈；不带令牌的地址行为与旧版完全一致（向下兼容）。
     */
    private var normalizedBase: String
    private var authToken: String? = null

    init {
        val (base, token) = DshAuthSession.splitBaseUrl(baseUrl)
        normalizedBase = normalizeBaseUrl(base)
        authToken = token
        DshAuthSession.configure(normalizedBase, authToken)
    }

    /**
     * 旧版（0.1.1-rc.2）浏览器信任围栏要求 Host 必须是受信权威，10.0.2.2 的 Host 会被拒，
     * 因此 debug 构建曾用此拦截器改写 Host。**新版（≥0.1.2-rc.1）围栏改用一次性令牌 +
     * 会话 Cookie：带有效令牌/Cookie 的请求不检查 Host**（2026-09-16 实测：模拟器无改写
     * 直连成功，带改写反而与 Cookie 的 JWT authority 不一致被判 401）。故新版下统一
     * **不改写 Host**，保证 Cookie authority 与请求 Host 一致。
     * 本拦截器已停用（保留代码注释备查）；若日后需要恢复，必须三处客户端同步加，
     * 且 Cookie 需在改写后的同一 Host 下重新建立。
     */
    private fun OkHttpClient.Builder.applyDebugEmulatorHostRewrite(): OkHttpClient.Builder {
        return this // 新版令牌认证下不做 Host 改写（见上注释）
    }

    /** 单次调用 HTTP 客户端：连接 10s / 读 30s（设计 §5.1）。
     *  拦截器顺序：先 debug 模拟器 Host 改写，后 [DshAuthSession.authInterceptor]——
     *  保证令牌换 Cookie 与业务请求使用同一 Host authority（Cookie JWT 绑定颁发 Host）。 */
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .applyDebugEmulatorHostRewrite()
        .addInterceptor(DshAuthSession.authInterceptor)
        .build()

    /**
     * WS 专用客户端（评审 B P1-1/P1-2 修正）：
     * - pingInterval 30s 防 NAT 半开（设计 §5.2 P2）；
     * - readTimeout 90s（> 2×ping）：握手等待 101 因此有界；升级后服务器对每个 ping 回 pong，
     *   健康链路的 pong 流量持续重置读计时；死链在 90s 内触发读超时 → onFailure → 重连回路可动；
     * - 独立 Dispatcher：WS 握手排队任务与 httpClient 的执行器隔离，关闭 WS 不影响单次调用。
     */
    private val webSocketClient: OkHttpClient = httpClient.newBuilder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .dispatcher(okhttp3.Dispatcher())
        .build()

    /** 活动事件流登记簿：shutdown/换址时逐一 cancel（升级后的 WS 不在连接池，evictAll 碰不到）。 */
    private val activeEventSockets =
        java.util.Collections.synchronizedList(ArrayList<WebSocket>())

    /**
     * 应答专用客户端（终审 P1-4）：连接 5s + 读 8s，总预算 13s < 广播 goAsync 的 ~10s ANR 窗口
     * 的安全边界（Android 15 实际限额较宽，但按最保守 10s 设计）。通知按钮应答必须快速落地，
     * 慢网下宁可返回 Transport 失败文案也不可拖垮广播队列。
     */
    private val respondClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .applyDebugEmulatorHostRewrite()
        .addInterceptor(DshAuthSession.authInterceptor)
        .build()

    /** 基址世代计数：每次换址自增，供上层诊断流与调用是否跨代（评审 B P1-4）。 */
    private val generationCounter = java.util.concurrent.atomic.AtomicLong(0L)

    /** 规范化：去尾部斜杠；https(wss) 保持。空输入保持原样由调用方校验。 */
    private fun normalizeBaseUrl(url: String): String = url.trim().trimEnd('/')

    /**
     * 换址（评审 B P1-4 修正）：更新基址 + 世代自增 + **立即关闭全部活动事件流**，
     * 迫使上层重连回路把两条流统一收拢到新址（否则存量 WS 留旧主机、单次调用打新主机，
     * 事件与调用分裂在两台服务器上）。
     */
    @Synchronized
    fun updateBaseUrl(url: String) {
        val (base, token) = DshAuthSession.splitBaseUrl(url)
        normalizedBase = normalizeBaseUrl(base)
        authToken = token
        DshAuthSession.configure(normalizedBase, authToken)
        generationCounter.incrementAndGet()
        closeAllEventSockets()
    }

    fun generation(): Long = generationCounter.get()

    @Synchronized
    fun currentBaseUrl(): String = normalizedBase

    // -----------------------------------------------------------------------
    // 通用调用
    // -----------------------------------------------------------------------

    /**
     * 通用单次调用：成功返回业务 value（可能为空 JsonObject，void 调用无 value 字段），
     * ok=false 归一 BizError，网络/协议层失败归一 NetError。
     */
    suspend fun call(method: String, payload: JsonObject): ApiResult<JsonObject> =
        withContext(Dispatchers.IO) {
            val rpcId = UUID.randomUUID().toString()
            val envelope = buildJsonObject {
                put("type", "client-request")
                put("rpcId", rpcId)
                put("method", method)
                put("payload", payload)
            }
            // URL 构造防御（评审 B P2-5）：非法/空白基址归一 NetError 而非抛 IllegalArgumentException
            val request = try {
                Request.Builder()
                    .url("${currentBaseUrl()}/api/$method")
                    .post(envelope.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .header("Accept", "application/json")
                    .build()
            } catch (e: IllegalArgumentException) {
                return@withContext ApiResult.NetError(e)
            }
            executeForValue(request)
        }

    /** 执行请求并把 server-response 信封折叠为三分支结果。 */
    private fun executeForValue(request: Request): ApiResult<JsonObject> {
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // 非 2xx：网关/隧道层错误（无业务信封），归一 NetError 携带状态码信息
                    return ApiResult.NetError(IOException("HTTP ${response.code}"))
                }
                val bodyText = response.body?.string().orEmpty()
                val envelope =
                    dev.dshmobile.model.FrameParser.parseServerResponse(bodyText)
                when {
                    envelope == null ->
                        ApiResult.NetError(IOException("malformed server-response envelope"))
                    envelope.result.ok -> when (val value = envelope.result.value) {
                        // void 调用的 value 可能缺失，归一为空对象方便调用方统一处理
                        null -> ApiResult.Ok(JsonObject(emptyMap()))
                        // 形状守卫（评审 B P2-9）：value 非对象 = 协议漂移，显式报错而非静默空结果
                        is JsonObject -> ApiResult.Ok(value)
                        else -> ApiResult.NetError(
                            IOException("unexpected non-object value in server-response")
                        )
                    }
                    envelope.result.error != null ->
                        ApiResult.BizError(envelope.result.error.code, envelope.result.error.message)
                    else ->
                        ApiResult.NetError(IOException("ok=false without error body"))
                }
            }
        } catch (e: Exception) {
            // Exception 而非 Throwable（评审 B P2-6）：OOM/SOE 等 Error 必须向上传播可见
            ApiResult.NetError(e)
        }
    }

    /**
     * 审批/提问应答：POST /api/respond，client-response 形态。
     * 回执三态由 [RespondReceipt] 承载；NotPending = 已在别处处理（撤通知，不重试）。
     */
    suspend fun respond(rpcId: String, value: JsonObject): RespondReceipt =
        withContext(Dispatchers.IO) {
            val envelope = buildJsonObject {
                put("type", "client-response")
                put("rpcId", rpcId)
                put("result", buildJsonObject {
                    put("ok", true)
                    put("value", value)
                })
            }
            val request = try {
                Request.Builder()
                    .url("${currentBaseUrl()}/api/respond")
                    .post(envelope.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            } catch (e: IllegalArgumentException) {
                // URL 防御（评审 B P2-5）
                return@withContext RespondReceipt.Transport("invalid-base-url: ${e.message}")
            }
            try {
                // 应答走短超时专用客户端（终审 P1-4：13s 总预算，广播 ANR 窗口内必返回）
                respondClient.newCall(request).execute().use { response ->
                    val bodyText = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        // 传输层故障（评审 B P1-3）：与回执体畸形分离，保留状态码
                        return@use RespondReceipt.Transport("HTTP ${response.code}: ${bodyText.take(120)}")
                    }
                    RespondReceipt.parse(bodyText)
                }
            } catch (e: Exception) {
                // 传输层故障（DNS/TLS/断连）：附异常类名，message 可能为 null 需兜底
                RespondReceipt.Transport("transport: ${e.javaClass.simpleName}: ${e.message.orEmpty()}")
            }
        }

    // -----------------------------------------------------------------------
    // 业务方法（薄封装，payload 形状全部对照 schema 源码）
    // -----------------------------------------------------------------------

    /** method="session.list"；payload cursor 为预留位 v1 不传。 */
    suspend fun sessionList(): ApiResult<List<SessionSummary>> {
        return when (val r = call("session.list", JsonObject(emptyMap()))) {
            is ApiResult.Ok -> mapValue(r) { DshJson.decodeFromJsonElement(SessionListValue.serializer(), it).items }
            is ApiResult.BizError -> r
            is ApiResult.NetError -> r
        }
    }

    /** method="workspace.list"；payload 为空对象字面量。 */
    suspend fun workspaceList(): ApiResult<WorkspaceListValue> {
        return when (val r = call("workspace.list", JsonObject(emptyMap()))) {
            is ApiResult.Ok -> mapValue(r) { DshJson.decodeFromJsonElement(WorkspaceListValue.serializer(), it) }
            is ApiResult.BizError -> r
            is ApiResult.NetError -> r
        }
    }

    /** method="session.create"；用 workspaceId 创建（新会话会正确挂到该工作区；用 cwd 创建不会挂工作区 → 落"未分组"）。 */
    suspend fun sessionCreate(workspaceId: String): ApiResult<String> {
        val payload = buildJsonObject { put("workspaceId", workspaceId) }
        return when (val r = call("session.create", payload)) {
            is ApiResult.Ok -> mapValue(r) { root ->
                // 响应值 {sessionId, agentPreset?}：提取 sessionId
                (root["sessionId"] as? JsonPrimitive)?.content
                    ?: throw IllegalStateException("session.create response missing sessionId")
            }
            is ApiResult.BizError -> r
            is ApiResult.NetError -> r
        }
    }

    /**
     * method="session.prompt"；mode="queue"，content 文本块数组，
     * clientTimeZone 取设备 IANA 时区（schema optional）。
     */
    suspend fun sessionPrompt(sessionId: String, text: String): ApiResult<Unit> {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("mode", "queue")
            put("content", JsonArray(listOf(buildJsonObject {
                put("type", "text")
                put("text", text)
            })))
            put("clientTimeZone", sanitizedClientTimeZone())
        }
        return when (val r = call("session.prompt", payload)) {
            is ApiResult.Ok -> ApiResult.Ok(Unit)
            is ApiResult.BizError -> r
            is ApiResult.NetError -> r
        }
    }

    /**
     * 宿主 schema 只接受 "UTC" 或 IANA Area/Location 名（如 Asia/Shanghai）。
     * 模拟器/部分设备的 ZoneId.systemDefault().id 是偏移格式（如 "GMT+08:00"），
     * 原样透传会被 host 以 invalid-time-zone 拒绝——发送静默失败、会话永远 blank
     * （2026-08-29 模拟器实测）。此处只放行 "UTC" 与含 "/" 的 IANA 名，其余降级 UTC。
     */
    internal fun sanitizedClientTimeZone(): String =
        sanitizedClientTimeZone(java.time.ZoneId.systemDefault().id)

    /** 纯函数重载（JVM 可确定性单测）：UTC 与含 "/" 的 IANA 名放行，其余（偏移格式等）降级 UTC。 */
    internal fun sanitizedClientTimeZone(zoneId: String): String =
        if (zoneId == "UTC" || zoneId.contains("/")) zoneId else "UTC"

    /** method="session.cancel"；响应 {accepted:true}。 */
    suspend fun sessionCancel(sessionId: String): ApiResult<Unit> {
        val payload = buildJsonObject { put("sessionId", sessionId) }
        return when (val r = call("session.cancel", payload)) {
            is ApiResult.Ok -> ApiResult.Ok(Unit)
            is ApiResult.BizError -> r
            is ApiResult.NetError -> r
        }
    }

    /** method="session.history"；拉取会话历史（对话页初始渲染，M5 原生）+ 解析为对话事件。 */
    suspend fun sessionHistory(sessionId: String, maxMessages: Int = 100): ApiResult<List<ConversationEvent>> {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("maxMessages", maxMessages)
        }
        return when (val r = call("session.history", payload)) {
            is ApiResult.Ok -> mapValue(r) { root ->
                val events = (root["events"] as? JsonArray) ?: JsonArray(emptyList())
                events.mapNotNull { el ->
                    val item = el as? JsonObject ?: return@mapNotNull null
                    val evt = item["event"] as? JsonObject ?: return@mapNotNull null
                    val type = (evt["type"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    val seq = (evt["seq"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
                    val time = (evt["time"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
                    val data = (evt["data"] as? JsonObject) ?: JsonObject(emptyMap())
                    // 宿主逐工具单行摘要（historyEntrySchema: {event, view}）：view.view.title
                    val viewTitle = (((item["view"] as? JsonObject)?.get("view") as? JsonObject)
                        ?.get("title") as? JsonPrimitive)?.contentOrNull
                    ConversationEvent.parse(type, seq, data, time, viewTitle)
                }
            }
            is ApiResult.BizError -> r
            is ApiResult.NetError -> r
        }
    }

    /** method="session.models"；拉可选模型目录 + 当前选择（需求：模型/推理等级选择器）。 */
    suspend fun sessionModels(sessionId: String): ApiResult<dev.dshmobile.model.ModelDirectory> {
        val payload = buildJsonObject { put("sessionId", sessionId) }
        return when (val r = call("session.models", payload)) {
            is ApiResult.Ok -> mapValue(r) { root ->
                dev.dshmobile.model.DshJson.decodeFromJsonElement(
                    dev.dshmobile.model.ModelDirectory.serializer(), root)
            }
            is ApiResult.BizError -> r
            is ApiResult.NetError -> r
        }
    }

    /** method="session.selectModel"；选择 provider/model/reasoningEffort。 */
    suspend fun sessionSelectModel(sessionId: String, provider: String, model: String, reasoningEffort: String?): ApiResult<Unit> {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("provider", provider)
            put("model", model)
            reasoningEffort?.let { put("reasoningEffort", it) }
        }
        return when (val r = call("session.selectModel", payload)) {
            is ApiResult.Ok -> ApiResult.Ok(Unit)
            is ApiResult.BizError -> r
            is ApiResult.NetError -> r
        }
    }

    /**
     * method="session.updateQueue"；排队消息控制（需求：编辑/删除排队消息）。
     * action 三态：edit(content 数组) / remove / steer。
     */
    suspend fun sessionUpdateQueue(sessionId: String, itemId: String, action: JsonObject): ApiResult<Unit> {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("itemId", itemId)
            put("action", action)
        }
        return when (val r = call("session.updateQueue", payload)) {
            is ApiResult.Ok -> ApiResult.Ok(Unit)
            is ApiResult.BizError -> r
            is ApiResult.NetError -> r
        }
    }

    /** 编辑排队消息 action：{kind:"edit", content:[{type:"text",text}]}。 */
    fun queueEditAction(text: String): JsonObject = buildJsonObject {
        put("kind", "edit")
        put("content", JsonArray(listOf(buildJsonObject { put("type", "text"); put("text", text) })))
    }

    /** 删除排队消息 action：{kind:"remove"}。 */
    fun queueRemoveAction(): JsonObject = buildJsonObject { put("kind", "remove") }

    /**
     * 插话排队消息 action：{kind:"steer"}（与 WebUI 每行"插话发送"按钮的 payload 逐字一致）。
     * host 前置校验（api-proxy updateQueue）：项仍在此轮 next-turn 收件箱且 agent 运行中才接受，
     * 否则返回 steer-unavailable；项已不在收件箱（已消费/会话未附加）返回 queue-item-not-found。
     * 注意不要带 target 字段：host 只读 action.kind，收件箱归属由 host 自行推导。
     */
    fun queueSteerAction(): JsonObject = buildJsonObject {
        put("kind", "steer")
    }

    /**
     * method="session.rename"；改会话标题。payload {sessionId, title} → {title, seq}。
     * 需求 5.4.1：重命名会话。返回是否成功（气泡由调用方刷新）。
     */
    suspend fun sessionRename(sessionId: String, title: String): ApiResult<Unit> {
        val payload = buildJsonObject {
            put("sessionId", sessionId)
            put("title", title)
        }
        return when (val r = call("session.rename", payload)) {
            is ApiResult.Ok -> ApiResult.Ok(Unit)
            is ApiResult.BizError -> r
            is ApiResult.NetError -> r
        }
    }

    /**
     * method="session.fork"；从当前会话分叉出子会话。payload {sessionId, atSeq?} → {sessionId}。
     * 需求 5.4.1：分叉会话。返回新子会话 id；失败返回 BizError/NetError。
     */
    suspend fun sessionFork(sessionId: String): ApiResult<String> {
        val payload = buildJsonObject { put("sessionId", sessionId) }
        return when (val r = call("session.fork", payload)) {
            is ApiResult.Ok -> mapValue(r) { root ->
                (root["sessionId"] as? JsonPrimitive)?.content
                    ?: throw IllegalStateException("session.fork response missing sessionId")
            }
            is ApiResult.BizError -> r
            is ApiResult.NetError -> r
        }
    }

    /**
     * method="workspace.archiveSession"；归档会话。payload {sessionId} → {archivedSessionIds}。
     * 需求 5.4.1：归档会话（从当前列表移除，进归档区）。
     */
    suspend fun workspaceArchiveSession(sessionId: String): ApiResult<Unit> {
        val payload = buildJsonObject { put("sessionId", sessionId) }
        return when (val r = call("workspace.archiveSession", payload)) {
            is ApiResult.Ok -> ApiResult.Ok(Unit)
            is ApiResult.BizError -> r
            is ApiResult.NetError -> r
        }
    }

    /** 审批应答 value：{sessionId, approvalId, outcome:"allowed-once"|"rejected"}。 */
    suspend fun respondApproval(
        rpcId: String,
        sessionId: String,
        approvalId: String,
        allow: Boolean,
    ): RespondReceipt {
        val value = buildJsonObject {
            put("sessionId", sessionId)
            put("approvalId", approvalId)
            put("outcome", if (allow) "allowed-once" else "rejected")
        }
        return respond(rpcId, value)
    }

    /** 提问应答 value：{sessionId, answer:{answers:[{id,selected,custom?}]}}。 */
    suspend fun respondQuestion(
        rpcId: String,
        sessionId: String,
        answers: List<QuestionAnswerItem>,
    ): RespondReceipt {
        val answersArray = JsonArray(answers.map { answer -> buildJsonObject {
            put("id", answer.id)
            put("selected", JsonArray(answer.selected.map { JsonPrimitive(it) }))
            answer.custom?.let { put("custom", it) }
        } })
        val value = buildJsonObject {
            put("sessionId", sessionId)
            put("answer", buildJsonObject { put("answers", answersArray) })
        }
        return respond(rpcId, value)
    }

    /**
     * 连通性自检：GET 基址，2xx/3xx 即通过（TLS 握手成功隐含）。
     * 任何异常（DNS/TLS/超时）→ false，设置页据此引导检查 Tailscale。
     */
    suspend fun probeConnectivity(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("${currentBaseUrl()}/").head().build()
            httpClient.newCall(request).execute().use { response ->
                // 3xx 也算可达（tailscale serve 可能重定向）；仅 4xx/5xx/异常判失败
                response.code in 200..399
            }
        } catch (e: Exception) {
            false
        }
    }

    // -----------------------------------------------------------------------
    // 事件流（M3 前台服务使用；此处仅提供工厂，业务回路在 EventStreamService）
    // -----------------------------------------------------------------------

    /**
     * 打开一条事件 WebSocket（path = "/api/events.mux" 或 "/api/events.host"）。
     * 协议翻转大小写不敏感（对齐浏览器 client.js L10132 的 URL 协议改写语义）。
     * 建立的套接字进入登记簿：shutdownEventStreams/updateBaseUrl 会主动关闭它。
     * 基址非法时抛 IllegalStateException（调用方为 M3 服务层，基址来自设置存储已规范化）。
     */
    fun openEventStream(path: String, listener: okhttp3.WebSocketListener): WebSocket {
        val url = currentBaseUrl()
            .replaceFirst("(?i)^https://".toRegex(), "wss://")
            .replaceFirst("(?i)^http://".toRegex(), "ws://") + path
        val request = try {
            val builder = Request.Builder().url(url)
            // 新版 dsh 令牌认证：WS 握手与 REST 同源，注入会话 Cookie（DshAuthSession 管理）
            DshAuthSession.currentCookie()?.let { builder.header("Cookie", it) }
            builder.build()
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("invalid base url for event stream: ${currentBaseUrl()}", e)
        }
        // 包装监听器：关闭/失败时从登记簿移除，防止登记簿泄漏
        val socket = webSocketClient.newWebSocket(request, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                listener.onOpen(webSocket, response)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                listener.onMessage(webSocket, text)
            }
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                listener.onMessage(webSocket, bytes)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosing(webSocket, code, reason)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                activeEventSockets.remove(webSocket)
                listener.onClosed(webSocket, code, reason)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                activeEventSockets.remove(webSocket)
                listener.onFailure(webSocket, t, response)
            }
        })
        activeEventSockets.add(socket)
        return socket
    }

    /**
     * 仅关闭活动事件流（保留 WS 执行器，供立即重连使用）。
     * 使用场景：网络切换重连、换址后的流收拢。
     */
    fun closeEventSockets() {
        closeAllEventSockets()
    }

    /**
     * 全量关停（评审 B P1-1 修正）：cancel() 立即断开全部活动流（含握手中的连接），
     * 随后关闭 WS 专属执行器。服务销毁时调用；重连场景请用 [closeEventSockets]。
     */
    fun shutdownEventStreams() {
        closeAllEventSockets()
        webSocketClient.dispatcher.executorService.shutdown()
    }

    /** 换址/停机共用：快照登记簿 → 清空 → 逐一 cancel（cancel 对握手中的连接同样生效）。 */
    private fun closeAllEventSockets() {
        val snapshot = synchronized(activeEventSockets) {
            val copy = ArrayList(activeEventSockets)
            activeEventSockets.clear()
            copy
        }
        snapshot.forEach { it.cancel() }
    }

    // -----------------------------------------------------------------------
    // 工具
    // -----------------------------------------------------------------------

    /** 把 Ok(JsonObject) 经转换器映射为业务类型；转换在 IO 线程执行（评审 B P2-7：大列表解码不卡主线程）。 */
    private suspend inline fun <T, R> mapValue(
        result: ApiResult<T>,
        crossinline converter: (T) -> R,
    ): ApiResult<R> {
        return when (result) {
            is ApiResult.Ok -> try {
                ApiResult.Ok(withContext(Dispatchers.IO) { converter(result.value) })
            } catch (e: Exception) {
                ApiResult.NetError(e)
            }
            is ApiResult.BizError -> result
            is ApiResult.NetError -> result
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /** 模拟器 NAT 宿主别名（10.0.2.2 = 宿主 127.0.0.1）。 */
        private const val EMULATOR_HOST_ALIAS = "10.0.2.2"

        /** 部署受信权威（与 dsh web --trusted-host 一致）：Host 无端口，匹配任意端口。
         *  值经 BuildConfig 注入（app/personal.properties，git 忽略）——源码不含私人域名（开源安全）。 */
        private val TRUSTED_AUTHORITY: String = BuildConfig.TRUSTED_AUTHORITY
    }
}
