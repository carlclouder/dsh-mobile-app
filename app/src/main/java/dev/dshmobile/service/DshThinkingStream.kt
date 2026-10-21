package dev.dshmobile.service

import dev.dshmobile.model.DshJson
import dev.dshmobile.model.ThinkingPreview
import dev.dshmobile.model.ThinkingPreviewState
import dev.dshmobile.network.DshApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID

/** 页面持有的单会话只读跟随器，临时思考内容与持久化消息链路分开。 */
class DshThinkingStream(
    private val api: DshApiClient,
    private val sessionId: String,
    private val log: (String) -> Unit = {},
    private val onHistoryRefresh: () -> Unit = {},
) {
    private class Connection(val generation: Long) {
        val streamId = UUID.randomUUID().toString()
        val state = ThinkingPreviewState()
        val completed = CompletableDeferred<Unit>()
        var socket: WebSocket? = null
        var dirty = false
        var receivedSnapshot = false
    }

    private val lock = Any()
    private val mutablePreview = MutableStateFlow(ThinkingPreview())
    val preview: StateFlow<ThinkingPreview> = mutablePreview.asStateFlow()
    private var active: Connection? = null
    @Volatile private var closed = false

    suspend fun run(): Unit = coroutineScope {
        var backoff = 1_000L
        while (isActive && !closed) {
            val connection = Connection(api.generation())
            synchronized(lock) {
                if (closed) return@coroutineScope
                active = connection
                mutablePreview.value = ThinkingPreview()
            }
            val publisher = launch {
                while (isActive) {
                    delay(80L)
                    synchronized(lock) {
                        if (active === connection && connection.generation != api.generation()) {
                            disconnect(connection)
                        } else if (isCurrent(connection) && connection.dirty) {
                            // 取值、核对连接及写入同锁，断连清空后不允许旧计时器把内容写回来。
                            mutablePreview.value = connection.state.preview
                            connection.dirty = false
                        }
                    }
                }
            }
            try {
                val socket = api.openEventStream("/api/remote.mux", listener(connection))
                synchronized(lock) {
                    if (isCurrent(connection)) connection.socket = socket else socket.cancel()
                }
                connection.completed.await()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                log("思考预览连接失败：${error.message.orEmpty()}")
            } finally {
                publisher.cancel()
                disconnect(connection)
            }
            if (connection.receivedSnapshot) backoff = 1_000L
            if (!closed) delay(backoff)
            backoff = (backoff * 2).coerceAtMost(15_000L)
        }
    }

    /** 页面离开只关闭本跟随器，不能影响前台服务的交互或工作区连接。 */
    fun close() = synchronized(lock) {
        closed = true
        active?.let(::disconnect)
        mutablePreview.value = ThinkingPreview()
    }

    private fun listener(connection: Connection) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response): Unit = synchronized(lock) {
            if (!isCurrent(connection)) {
                webSocket.cancel()
                disconnect(connection)
                return@synchronized
            }
            connection.socket = webSocket
            val request = buildJsonObject {
                put("type", "open")
                put("streamId", connection.streamId)
                put("endpoint", "session/follow")
                put("payload", buildJsonObject {
                    put("args", buildJsonObject {
                        put("request", buildJsonObject {
                            put("address", buildJsonObject {
                                put("kind", "session")
                                put("sessionId", sessionId)
                            })
                            put("assistantStream", true)
                            put("maxMessages", 1)
                        })
                    })
                })
            }
            if (!webSocket.send(request.toString())) disconnect(connection)
        }

        override fun onMessage(webSocket: WebSocket, text: String): Unit = synchronized(lock) {
            if (!isCurrent(connection)) {
                disconnect(connection)
                return@synchronized
            }
            val frame = try { DshJson.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }
            if (frame == null) {
                log("思考预览收到畸形帧，重新获取快照")
                disconnect(connection)
                return@synchronized
            }
            if (frame.string("streamId") != connection.streamId) return@synchronized
            when (frame.string("type")) {
                "item" -> {
                    val value = frame["value"] as? JsonObject
                    if (value == null || !connection.state.accept(value)) {
                        log("思考预览序列不连续或格式异常，重新获取快照")
                        disconnect(connection)
                        return@synchronized
                    }
                    if (value.string("type") == "snapshot") connection.receivedSnapshot = true
                    if (connection.state.hasPreview) {
                        connection.dirty = true
                    } else {
                        // 结束、正文开始和空快照立即清理，不能被增量发布节流拖延。
                        mutablePreview.value = ThinkingPreview()
                        connection.dirty = false
                    }
                    val isSnapshot = value.string("type") == "snapshot"
                    val isAttemptEnd = value.string("type") == "assistant-stream" &&
                        (value["frame"] as? JsonObject)?.string("type") == "end"
                    if ((isSnapshot || isAttemptEnd) && isCurrent(connection)) {
                        // 长思考时可能未读到 running=true；快照和已验证的生成结束都要补查历史。
                        // 回调只调度页面已有请求闸门，不在套接字锁内执行网络读取，也不修改回合状态。
                        onHistoryRefresh()
                    }
                }
                "end", "error" -> disconnect(connection)
                else -> {
                    log("思考预览收到未知流帧，重新获取快照")
                    disconnect(connection)
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = disconnect(connection)

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = disconnect(connection)

        override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
            log("思考预览连接已断开：${error.message.orEmpty()}")
            disconnect(connection)
        }
    }

    private fun disconnect(connection: Connection): Unit = synchronized(lock) {
        if (active === connection) {
            active = null
            mutablePreview.value = ThinkingPreview()
        }
        connection.dirty = false
        connection.socket?.cancel()
        connection.completed.complete(Unit)
    }

    private fun isCurrent(connection: Connection): Boolean =
        !closed && active === connection && connection.generation == api.generation()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
