"""为 App 接入新版 dsh 的工作区流（/api/remote.mux + workspace/follow）。

背景（2026-09-16 用户报障 + 源码实证）：
宿主升级到 0.1.5-rc.2 后，App 出现"工作区子目录消失、所有会话无分类、大量历史会话重新显示"。
根因：新版 dsh 的 `workspace/list` 端点不存在（只有 `workspace/follow` 流），
而 App 的工作区分组与归档会话隐藏都依赖该数据 → 拿不到就退化为"无分类 + 归档全显示"。

官方实现（dsh-api-gateway 源码实证）：
- 所有流式 RPC 走同一个 WebSocket 端点 `/api/remote.mux`（REMOTE_STREAM_MUX_PATH）；
- 客户端开流帧：{"type":"open","streamId":"<uuid>","endpoint":"workspace/follow","payload":{}}
- 服务端帧：{"type":"item"|"end"|"error","streamId":...,"value"|"error":...}
- workspace 帧类型：baseline{items,archivedSessionIds} / upsert{workspace} / remove{workspaceId}
  / order{workspaceIds} / archived{archivedSessionIds}
- 服务端每 2 秒发 WS Ping，OkHttp 自动回 Pong（无需自实现）

本补丁在 EventStreamService 中新增工作区流订阅，并在轮询对账里跳过已失效的 REST 调用。
"""
import sys

path = r"D:\AI任务\dsh-mobile-app\app\src\main\java\dev\dshmobile\service\EventStreamService.kt"
text = open(path, encoding="utf-8").read()

# ---------- 1) 字段：工作区流 job + 就绪标志 ----------
old_fields = """    /** 轮询协程（新版 dsh 无 WS 事件流，以周期全量对账驱动状态；见 startStreams 注释）。 */
    private var pollJob: Job? = null"""
new_fields = """    /** 轮询协程（新版 dsh 无 WS 事件流，以周期全量对账驱动状态；见 startStreams 注释）。 */
    private var pollJob: Job? = null

    /** 工作区订阅协程（新版 dsh：工作区与归档集合只存在于 `/api/remote.mux` 的 workspace/follow 流）。 */
    private var workspaceJob: Job? = null

    /**
     * 工作区流是否已就绪（收到过 baseline）。
     * 新版宿主的 REST 端点 `workspace.list` 已不存在（实测 404），流就绪后不再发该请求；
     * 旧版宿主（点号协议）没有此流，仍走 REST 对账。
     */
    @Volatile private var workspaceStreamReady = false"""
if old_fields not in text:
    sys.exit("字段锚点未找到")
text = text.replace(old_fields, new_fields, 1)

# ---------- 2) 启动：轮询 + 工作区流 ----------
old_start = """    private fun startStreams() {
        pollJob?.cancel()
        pollJob = serviceScope.launch { runPollingLoop() }
    }"""
new_start = """    private fun startStreams() {
        pollJob?.cancel()
        pollJob = serviceScope.launch { runPollingLoop() }
        workspaceJob?.cancel()
        workspaceJob = serviceScope.launch { runWorkspaceStream() }
    }

    // -------------------------------------------------------------------
    // 工作区订阅（新版 dsh：/api/remote.mux 上的 workspace/follow 流）
    // -------------------------------------------------------------------

    /** 单条服务端帧的解析结果（只保留本类关心的三种分支）。 */
    private sealed interface MuxServerFrame {
        /** item 帧携带的业务帧（JsonObject）。 */
        data class Item(val value: kotlinx.serialization.json.JsonObject) : MuxServerFrame
        /** end / error 或无法解析的帧：表示该逻辑流已终止。 */
        data object Terminal : MuxServerFrame
    }

    /**
     * 解析 `/api/remote.mux` 的一条服务端文本帧。
     * 协议（dsh-api-gateway/stream-protocol.js 实证）：
     * `{"type":"item","streamId":..,"value":<业务帧>}` / `{"type":"end",...}` / `{"type":"error",...}`。
     * 只做最小解析：拿到 item 的 value 即可，其余一律视为终止（触发重连）。
     */
    private fun parseMuxFrame(text: String): MuxServerFrame {
        val obj = try {
            kotlinx.serialization.json.Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject
        } catch (e: Exception) {
            null
        } ?: return MuxServerFrame.Terminal
        return when ((obj["type"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull) {
            "item" -> (obj["value"] as? kotlinx.serialization.json.JsonObject)
                ?.let { MuxServerFrame.Item(it) } ?: MuxServerFrame.Terminal
            else -> MuxServerFrame.Terminal
        }
    }

    /**
     * 处理一个工作区业务帧（WorkspaceFollowFrame）：
     * - baseline：全量 items + 归档集合（首次订阅即拿到，替代已失效的 workspace.list）；
     * - upsert / remove：增量维护列表（复用 upsertWorkspace 的合并语义，避免整表覆盖）；
     * - archived：归档集合替换（决定列表隐藏与是否弹通知）；
     * - order：仅影响展示顺序，按 workspaceId 排序即可，忽略。
     */
    private fun handleWorkspaceFrame(frame: kotlinx.serialization.json.JsonObject) {
        val type = (frame["type"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: return
        when (type) {
            "baseline" -> {
                val items = (frame["items"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { el ->
                    try {
                        dev.dshmobile.model.DshJson.decodeFromJsonElement(
                            dev.dshmobile.model.WorkspaceView.serializer(), el)
                    } catch (e: Exception) {
                        null
                    }
                } ?: emptyList()
                DshRepository.publishWorkspaces(items.sortedBy { it.workspaceId })
                applyArchivedSet(frame["archivedSessionIds"])
                workspaceStreamReady = true
            }
            "upsert" -> (frame["workspace"] as? kotlinx.serialization.json.JsonObject)?.let { ws ->
                try {
                    upsertWorkspace(dev.dshmobile.model.DshJson.decodeFromJsonElement(
                        dev.dshmobile.model.WorkspaceView.serializer(), ws))
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "workspace upsert parse failed: ${e.message}")
                }
            }
            "remove" -> {
                val id = (frame["workspaceId"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                if (id != null) {
                    DshRepository.publishWorkspaces(
                        DshRepository.workspaces.value.filter { it.workspaceId != id })
                }
            }
            "archived" -> applyArchivedSet(frame["archivedSessionIds"])
            else -> Unit   // order 等帧不影响本端展示语义
        }
    }

    /** 应用归档集合：注入状态机（快照排除归档 → 列表隐藏/不通知）并刷新快照。 */
    private fun applyArchivedSet(element: kotlinx.serialization.json.JsonElement?) {
        val ids = (element as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
            ?: return
        synchronized(machineLock) {
            stateMachine.setArchivedSessionIds(ids)
            publishSnapshot()
        }
    }

    /**
     * 工作区流的永久回路：连 `/api/remote.mux` → 开 workspace/follow 流 → 收帧。
     * 断线（end/error/socket 关闭）后退避重连；换址由外层 restartStreams 触发取消重建。
     */
    private suspend fun runWorkspaceStream() {
        var backoffMs = 1_000L
        while (coroutineContext.isActive) {
            val streamId = java.util.UUID.randomUUID().toString()
            val closed = CompletableDeferred<Unit>()
            val openedFlag = java.util.concurrent.atomic.AtomicBoolean(false)
            // 开流请求：{"type":"open","streamId","endpoint","payload"}（payload 为命名参数对象，本流无参）
            val openFrame = kotlinx.serialization.json.buildJsonObject {
                put("type", "open")
                put("streamId", streamId)
                put("endpoint", "workspace/follow")
                put("payload", kotlinx.serialization.json.JsonObject(emptyMap()))
            }
            val listener = object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    openedFlag.set(true)
                    webSocket.send(openFrame.toString())
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        when (val frame = parseMuxFrame(text)) {
                            is MuxServerFrame.Item -> handleWorkspaceFrame(frame.value)
                            MuxServerFrame.Terminal -> closed.complete(Unit)
                        }
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "workspace frame handling failed: ${e.message}")
                    }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    closed.complete(Unit)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closed.complete(Unit)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                    android.util.Log.w(TAG, "workspace stream failed: ${t.message}")
                    closed.complete(Unit)
                }
            }
            try {
                DshRepository.apiClient.openEventStream("/api/remote.mux", listener)
            } catch (e: IllegalStateException) {
                DshRepository.publishConnection(DshRepository.ConnectionState.UNREACHABLE)
                delay(60_000L)
                continue
            }
            closed.await()
            workspaceStreamReady = false
            if (openedFlag.get()) backoffMs = 1_000L
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(15_000L)
        }
    }"""
if old_start not in text:
    sys.exit("startStreams 锚点未找到")
text = text.replace(old_start, new_start, 1)

# ---------- 3) 轮询对账：流就绪后跳过已失效的 REST 调用 ----------
old_ws = """        // 工作区列表（M4 新建会话数据源 + 需求 2.9.3 归档集注入；失败不阻塞会话链路）
        when (val result = DshRepository.apiClient.workspaceList()) {"""
new_ws = """        // 工作区列表（旧版宿主的 REST 路径）。新版 dsh 已移除 workspace.list（实测 404），
        // 工作区与归档集合改由 `/api/remote.mux` 的 workspace/follow 流提供（见 runWorkspaceStream）；
        // 流就绪后跳过本段，避免每轮轮询发一次注定 404 的请求。
        if (workspaceStreamReady) return sessionListOk
        when (val result = DshRepository.apiClient.workspaceList()) {"""
if old_ws not in text:
    sys.exit("workspaceList 锚点未找到")
text = text.replace(old_ws, new_ws, 1)

with open(path, "w", encoding="utf-8", newline="") as handle:
    handle.write(text)

# 回读断言
check = open(path, encoding="utf-8").read()
for needle in ("runWorkspaceStream", "workspace/follow", "/api/remote.mux", "handleWorkspaceFrame",
               "workspaceStreamReady", "return sessionListOk"):
    if needle not in check:
        sys.exit(f"回读断言失败：缺少 {needle}")
print("patch ok：工作区流订阅已接入，回读断言全部通过")
