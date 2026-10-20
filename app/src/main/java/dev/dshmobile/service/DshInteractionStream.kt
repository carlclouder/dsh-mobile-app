package dev.dshmobile.service

import dev.dshmobile.model.DshJson
import dev.dshmobile.model.InteractionTarget
import dev.dshmobile.model.QuestionItem
import dev.dshmobile.network.DshApiClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** 现代交互与限时等待共用一个物理连接；状态、计时器和回答地址在同一把锁内换代。 */
class DshInteractionStream(
    private val api: DshApiClient,
    private val scope: CoroutineScope,
    private val stateMachine: NotificationStateMachine,
    private val lock: Any,
    private val publish: (List<NotificationStateMachine.Command>) -> Unit,
    private val refresh: (String) -> Unit,
    private val log: (String) -> Unit = {},
) {
    private class Connection(val generation: Long) {
        val streamId = UUID.randomUUID().toString()
        val closed = CompletableDeferred<Unit>()
        var socket: WebSocket? = null
        var clientId: String? = null
        var immediateReconnect = false
        val waits = LinkedHashMap<String, WaitClaim>()
    }

    private class WaitClaim(val info: NotificationStateMachine.QuestionInfo, val target: InteractionTarget.Remote) {
        val streamId = UUID.randomUUID().toString()
        var receivedDuration = false
        var timer: Job? = null
    }

    private var active: Connection? = null

    suspend fun run() {
        var backoff = 1_000L
        while (coroutineContext.isActive) {
            val connection = Connection(api.generation())
            synchronized(lock) {
                if (connection.generation == api.generation()) {
                    publish(stateMachine.onInteractionHostGeneration(connection.generation))
                    active = connection
                } else connection.closed.complete(Unit)
            }
            try {
                val socket = api.openEventStream("/api/remote.mux", listener(connection))
                synchronized(lock) {
                    if (active === connection) connection.socket = socket else socket.cancel()
                }
                connection.closed.await()
            } catch (error: IllegalStateException) {
                log("交互连接地址无效：${error.message}")
            } finally {
                disconnect(connection)
            }
            if (connection.clientId != null) backoff = 1_000L
            if (!connection.immediateReconnect) delay(backoff)
            backoff = (backoff * 2).coerceAtMost(15_000L)
        }
    }

    fun reconnect() = synchronized(lock) {
        active?.let { connection ->
            connection.immediateReconnect = true
            disconnect(connection)
        }
    }

    fun close() = synchronized(lock) { active?.let(::disconnect) }

    fun submitted(target: InteractionTarget): Unit = synchronized(lock) {
        when (target) {
            is InteractionTarget.Remote -> {
                val connection = active
                if (connection != null && connection.waits[target.eventId]?.target == target) releaseWait(connection, target.eventId)
                publish(stateMachine.onRemoteCancelled(target))
            }
            is InteractionTarget.Continued -> publish(stateMachine.onContinuedSubmitted(target))
        }
    }

    private fun listener(connection: Connection) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response): Unit = synchronized(lock) {
            if (!isCurrent(connection)) {
                webSocket.cancel()
                return@synchronized
            }
            connection.socket = webSocket
            webSocket.send(open(connection.streamId, "\$events", JsonObject(emptyMap())).toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String): Unit = synchronized(lock) {
            if (!isCurrent(connection)) {
                webSocket.cancel()
                connection.closed.complete(Unit)
                return@synchronized
            }
            val frame = try { Json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }
            if (frame == null) {
                log("交互连接收到无法解析的帧，重新连接以接收待答重放")
                disconnect(connection)
                return@synchronized
            }
            val streamId = frame.string("streamId") ?: return@synchronized
            if (streamId == connection.streamId) {
                when (frame.string("type")) {
                    "item" -> (frame["value"] as? JsonObject)?.let { handleEvent(connection, it) }
                    "end", "error" -> {
                        if (frame.string("type") == "error") log("现代交互订阅失败：${frame["error"]}")
                        disconnect(connection)
                    }
                }
            } else {
                val claim = connection.waits.values.firstOrNull { it.streamId == streamId } ?: return@synchronized
                handleWait(connection, claim, frame)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            disconnect(connection)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            disconnect(connection)
        }

        override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
            log("现代交互连接已断开：${error.message}")
            disconnect(connection)
        }
    }

    private fun handleEvent(connection: Connection, frame: JsonObject) {
        if (frame.string("type") == "ready") {
            val clientId = frame.string("clientId") ?: return
            if (connection.clientId != null && connection.clientId != clientId) {
                disconnect(connection)
                return
            }
            connection.clientId = clientId
            api.setInteractionClient(clientId)
            publish(stateMachine.onRemoteReady())
            return
        }
        val target = InteractionTarget.Remote(connection.clientId ?: return, frame.string("eventId") ?: return)
        if (frame.string("type") == "cancel") {
            val sessionId = stateMachine.pendingQuestion(target.eventId)?.sessionId
            releaseWait(connection, target.eventId)
            publish(stateMachine.onRemoteCancelled(target))
            sessionId?.let(refresh)
            return
        }
        if (frame.string("type") != "waterfall") return
        val sessionId = frame.string("sessionId") ?: frame.string("agentId")
        val request = frame["request"] as? JsonObject
        if (sessionId == null || request == null) {
            delegate(connection, target)
            return
        }
        when (frame.string("event")) {
            "approval/request" -> {
                val toolName = request.string("toolName")
                if (toolName == null) {
                    delegate(connection, target)
                    return
                }
                publish(stateMachine.onRemoteApproval(NotificationStateMachine.ApprovalInfo(
                    rpcId = target.eventId,
                    sessionId = sessionId,
                    approvalId = target.eventId,
                    toolName = toolName,
                    reason = request.string("reason"),
                    target = target,
                )))
            }
            "user-questions/request" -> {
                val questions = parseQuestions(request["questions"] as? JsonArray)
                val wait = request["wait"] as? JsonObject
                val timedFlag = (wait?.get("timed") as? JsonPrimitive)?.booleanOrNull
                val timed = timedFlag == true
                val callId = wait?.string("callId")
                if (questions == null || (request.containsKey("wait") &&
                    (wait == null || (wait.containsKey("timed") && timedFlag == null) || callId == null))) {
                    delegate(connection, target)
                    return
                }
                val info = NotificationStateMachine.QuestionInfo(
                    rpcId = target.eventId,
                    sessionId = sessionId,
                    firstQuestionText = questions.first().question,
                    questions = questions,
                    target = target,
                    available = !timed,
                    callId = callId,
                )
                // 先建立等待声明，首帧确认剩余时间之后再开放提交；重放不能重复持有声明。
                val existing = connection.waits[target.eventId]
                if (existing != null) return
                connection.waits.values.filter { it.info.key == info.key }.map { it.target.eventId }
                    .forEach { releaseWait(connection, it) }
                publish(stateMachine.onRemoteQuestion(info))
                if (timed) {
                    val claim = WaitClaim(info, target)
                    connection.waits[target.eventId] = claim
                    val args = buildJsonObject {
                        put("agentId", frame.string("agentId") ?: sessionId)
                        put("callId", callId)
                    }
                    if (connection.socket?.send(open(claim.streamId, "userQuestions/attachWait", args).toString()) != true) {
                        releaseWait(connection, target.eventId)
                        publish(stateMachine.onRemoteCancelled(target))
                        delegate(connection, target)
                    }
                }
            }
            else -> delegate(connection, target)
        }
    }

    private fun handleWait(connection: Connection, claim: WaitClaim, frame: JsonObject) {
        when (frame.string("type")) {
            "item" -> {
                if (claim.receivedDuration) return
                if (stateMachine.pendingQuestion(claim.target.eventId)?.target != claim.target) {
                    releaseWait(connection, claim.target.eventId)
                    return
                }
                val duration = ((frame["value"] as? JsonObject)?.get("remainingMs") as? JsonPrimitive)?.longOrNull
                if (duration == null || duration < 0 || duration > Int.MAX_VALUE.toLong()) {
                    releaseWait(connection, claim.target.eventId)
                    publish(stateMachine.onRemoteCancelled(claim.target))
                    delegate(connection, claim.target)
                    refresh(claim.info.sessionId)
                    return
                }
                claim.receivedDuration = true
                publish(stateMachine.onRemoteQuestion(claim.info.copy(available = true)))
                claim.timer = scope.launch {
                    delay(duration)
                    synchronized(lock) {
                        if (!isCurrent(connection) || connection.waits[claim.target.eventId] !== claim) return@launch
                        publish(stateMachine.onRemoteCancelled(claim.target))
                        val outcome = buildJsonObject {
                            put("kind", "rejected")
                            put("error", buildJsonObject {
                                put("name", "UserQuestionError")
                                put("code", "ASK_TIMED_OUT")
                                put("message", "回答等待时间已结束")
                            })
                        }
                        // 超时拒绝和声明释放均有界；不让 App 的声明无限暂停宿主倒计时。
                        scope.launch { api.sendInteractionOutcome(claim.target, outcome) }
                        releaseWait(connection, claim.target.eventId)
                        refresh(claim.info.sessionId)
                    }
                }
            }
            "end", "error" -> {
                releaseWait(connection, claim.target.eventId)
                publish(stateMachine.onRemoteCancelled(claim.target))
                if (!claim.receivedDuration) delegate(connection, claim.target)
                refresh(claim.info.sessionId)
            }
        }
    }

    private fun delegate(connection: Connection, target: InteractionTarget.Remote) {
        log("交互未能呈现，交由其他客户端处理：${target.eventId}")
        scope.launch {
            if (synchronized(lock) { isCurrent(connection) && connection.clientId == target.clientId }) {
                api.sendInteractionOutcome(target, buildJsonObject { put("kind", "next") })
            }
        }
    }

    private fun releaseWait(connection: Connection, eventId: String) {
        val claim = connection.waits.remove(eventId) ?: return
        claim.timer?.cancel()
        connection.socket?.send(buildJsonObject {
            put("type", "cancel")
            put("streamId", claim.streamId)
        }.toString())
    }

    private fun disconnect(connection: Connection): Unit = synchronized(lock) {
        connection.waits.keys.toList().forEach { releaseWait(connection, it) }
        if (active === connection) {
            active = null
            api.setInteractionClient(null)
            connection.clientId?.let { publish(stateMachine.onRemoteDisconnected(it)) }
        }
        connection.socket?.cancel()
        connection.closed.complete(Unit)
    }

    private fun isCurrent(connection: Connection): Boolean =
        active === connection && connection.generation == api.generation()

    private fun parseQuestions(array: JsonArray?): List<QuestionItem>? = try {
        array?.map { DshJson.decodeFromJsonElement(QuestionItem.serializer(), it) }
            ?.takeIf { questions -> questions.isNotEmpty() && questions.map { it.id }.distinct().size == questions.size &&
                questions.all { it.id.isNotBlank() && it.question.isNotBlank() } }
    } catch (_: Exception) { null }

    private fun open(streamId: String, endpoint: String, args: JsonObject) = buildJsonObject {
        put("type", "open")
        put("streamId", streamId)
        put("endpoint", endpoint)
        put("payload", buildJsonObject { put("args", args) })
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
}
