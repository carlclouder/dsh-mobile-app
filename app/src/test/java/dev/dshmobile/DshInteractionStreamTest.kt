package dev.dshmobile

import dev.dshmobile.model.DshJson
import dev.dshmobile.model.InteractionTarget
import dev.dshmobile.model.RespondReceipt
import dev.dshmobile.network.DshApiClient
import dev.dshmobile.service.DshInteractionStream
import dev.dshmobile.service.NotificationStateMachine
import dev.dshmobile.service.NotificationStateMachine.Command
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 真实 WebSocket 下行、等待声明、HTTP 应答与通知状态机之间的完整交互验证。 */
class DshInteractionStreamTest {
    @Test
    fun `无限等待仅提供callId时显示可回答的问题`() {
        Harness().use { harness ->
            harness.start()
            harness.sendEvent(jsonObject(
                """{"type":"waterfall","event":"user-questions/request","eventId":"question-unlimited","agentId":"session-1","request":{"wait":{"callId":"call-unlimited"},"questions":[{"id":"q1","question":"选择哪个方案？","options":[{"label":"方案甲","description":"保留现有接口"}]}]}}""",
            ))

            val shown = assertIs<Command.ShowQuestion>(harness.awaitCommand {
                it is Command.ShowQuestion && it.info.rpcId == "question-unlimited"
            })

            assertTrue(shown.info.available)
            assertEquals("call-unlimited", shown.info.callId)
            assertEquals("session-1", shown.info.sessionId)
            assertEquals("保留现有接口", shown.info.questions.single().options.single().description)
            assertEquals(InteractionTarget.Remote(CLIENT_ID, "question-unlimited"), shown.info.target)
            assertFalse(harness.clientFrames.any { it["endpoint"] == JsonPrimitive("userQuestions/attachWait") })
            assertNull(harness.httpRequests.poll(150, TimeUnit.MILLISECONDS), "合法问题不能被错误委派")
        }
    }

    @Test
    fun `限时等待按宿主剩余时间超时并发送标准拒绝和释放声明`() {
        Harness().use { harness ->
            harness.start()
            harness.sendEvent(jsonObject(
                """{"type":"waterfall","event":"user-questions/request","eventId":"question-timed","agentId":"session-1","request":{"wait":{"callId":"call-timed","timed":true},"questions":[{"id":"q1","question":"是否继续？"}]}}""",
            ))
            val opened = harness.awaitClientFrame { it["endpoint"] == JsonPrimitive("userQuestions/attachWait") }
            assertEquals(jsonObject("""{"args":{"agentId":"session-1","callId":"call-timed"}}"""), opened["payload"])
            assertFalse(assertNotNull(harness.pendingQuestion("question-timed")).available)
            val waitStreamId = opened.getValue("streamId").jsonPrimitive.content

            harness.sendFrame(buildJsonObject {
                put("type", "item")
                put("streamId", waitStreamId)
                put("value", buildJsonObject { put("remainingMs", 300) })
            })
            val shown = assertIs<Command.ShowQuestion>(harness.awaitCommand {
                it is Command.ShowQuestion && it.info.rpcId == "question-timed"
            })
            assertTrue(shown.info.available)

            val submitted = assertNotNull(harness.httpRequests.poll(3, TimeUnit.SECONDS), "本地计时到期必须提交超时结果")
            assertEquals("/api/\$events/result", submitted.path)
            val args = jsonObject(submitted.body.readUtf8()).getValue("payload").jsonObject.getValue("args").jsonObject
            assertEquals(jsonObject(
                """{"clientId":"stream-client","eventId":"question-timed","outcome":{"kind":"rejected","error":{"name":"UserQuestionError","code":"ASK_TIMED_OUT","message":"回答等待时间已结束"}}}""",
            ), args)
            val cancelled = harness.awaitClientFrame {
                it["type"] == JsonPrimitive("cancel") && it["streamId"] == JsonPrimitive(waitStreamId)
            }
            assertEquals(setOf("type", "streamId"), cancelled.keys)
            val pending = assertNotNull(harness.pendingQuestion("question-timed"))
            assertFalse(pending.available)
            assertNull(pending.target)
            assertEquals("session-1", harness.refreshes.poll(3, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `未知交互通过next交给其他客户端且不产生挂起卡片`() {
        Harness().use { harness ->
            harness.start()
            harness.sendEvent(jsonObject(
                """{"type":"waterfall","event":"future/request","eventId":"event-unknown","agentId":"session-1","request":{"extension":"新版本功能"}}""",
            ))

            val submitted = assertNotNull(harness.httpRequests.poll(3, TimeUnit.SECONDS))

            assertEquals("/api/\$events/result", submitted.path)
            val args = jsonObject(submitted.body.readUtf8()).getValue("payload").jsonObject.getValue("args")
            assertEquals(jsonObject(
                """{"clientId":"stream-client","eventId":"event-unknown","outcome":{"kind":"next"}}""",
            ), args)
            synchronized(harness.lock) {
                assertTrue(harness.stateMachine.snapshotApprovals().isEmpty())
                assertTrue(harness.stateMachine.snapshotQuestions().isEmpty())
            }
        }
    }

    @Test
    fun `审批成功提交后即使没有宿主cancel也会撤销本地审批`() = runBlocking {
        Harness().use { harness ->
            harness.start()
            harness.sendEvent(jsonObject(
                """{"type":"waterfall","event":"approval/request","eventId":"approval-1","agentId":"session-1","request":{"toolName":"shell","reason":"安装依赖"}}""",
            ))
            val shown = assertIs<Command.ShowApproval>(harness.awaitCommand { it is Command.ShowApproval })
            val target = assertIs<InteractionTarget.Remote>(shown.info.target)

            val receipt = harness.api.respondApproval(
                shown.info.rpcId, shown.info.sessionId, shown.info.approvalId, true, target,
            )
            assertIs<RespondReceipt.Accepted>(receipt)
            harness.stream.submitted(target)

            assertEquals(Command.CancelApproval("approval-1"), harness.awaitCommand { it is Command.CancelApproval })
            synchronized(harness.lock) {
                assertNull(harness.stateMachine.pendingApproval("approval-1"))
            }
            val submitted = assertNotNull(harness.httpRequests.poll(3, TimeUnit.SECONDS))
            assertEquals("/api/\$events/result", submitted.path)
            val args = jsonObject(submitted.body.readUtf8()).getValue("payload").jsonObject.getValue("args").jsonObject
            assertEquals(jsonObject("""{"kind":"result","value":"allowed-once"}"""), args["outcome"])
            // 服务端夹具只发送 ready 与 waterfall，从未发送 cancel，覆盖成功回执之后的本地收敛。
            assertEquals(2, harness.sentEventCount)
        }
    }

    private class Harness : AutoCloseable {
        val lock = Any()
        val stateMachine = NotificationStateMachine()
        val clientFrames = CopyOnWriteArrayList<JsonObject>()
        val httpRequests = LinkedBlockingQueue<RecordedRequest>()
        val refreshes = LinkedBlockingQueue<String>()
        private val commands = LinkedBlockingQueue<Command>()
        private val uplink = LinkedBlockingQueue<JsonObject>()
        private val ready = CountDownLatch(1)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val server = MockWebServer()
        private var running: Job? = null
        @Volatile private var socket: WebSocket? = null
        @Volatile private var eventStreamId: String? = null
        @Volatile var sentEventCount = 0
            private set
        val api: DshApiClient
        val stream: DshInteractionStream

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/api/remote.mux" -> MockResponse().withWebSocketUpgrade(serverListener())
                    "/api/\$events/result" -> {
                        httpRequests.put(request)
                        MockResponse().setHeader("Content-Type", "application/json").setBody(
                            """{"type":"server-response","rpcId":"response","result":{"ok":true}}""",
                        )
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            api = DshApiClient(server.url("/").toString())
            stream = DshInteractionStream(
                api = api,
                scope = scope,
                stateMachine = stateMachine,
                lock = lock,
                publish = { emitted ->
                    commands.addAll(emitted)
                    if (api.isInteractionClientActive(CLIENT_ID)) ready.countDown()
                },
                refresh = { refreshes.put(it) },
            )
        }

        fun start() {
            running = scope.launch { stream.run() }
            assertTrue(ready.await(3, TimeUnit.SECONDS), "现代事件流必须完成握手并接收ready")
        }

        fun sendEvent(event: JsonObject) {
            sentEventCount++
            sendFrame(buildJsonObject {
                put("type", "item")
                put("streamId", assertNotNull(eventStreamId))
                put("value", event)
            })
        }

        fun sendFrame(frame: JsonObject) {
            assertTrue(assertNotNull(socket).send(frame.toString()), "服务端帧必须发送到真实WebSocket")
        }

        fun pendingQuestion(eventId: String): NotificationStateMachine.QuestionInfo? = synchronized(lock) {
            stateMachine.pendingQuestion(eventId)
        }

        fun awaitCommand(predicate: (Command) -> Boolean): Command {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (true) {
                val remaining = deadline - System.nanoTime()
                assertTrue(remaining > 0, "未收到预期的状态机通知指令")
                val command = assertNotNull(commands.poll(remaining, TimeUnit.NANOSECONDS))
                if (predicate(command)) return command
            }
        }

        fun awaitClientFrame(predicate: (JsonObject) -> Boolean): JsonObject {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (true) {
                val remaining = deadline - System.nanoTime()
                assertTrue(remaining > 0, "未收到预期的客户端WebSocket帧")
                val frame = assertNotNull(uplink.poll(remaining, TimeUnit.NANOSECONDS))
                if (predicate(frame)) return frame
            }
        }

        private fun serverListener() = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = jsonObject(text)
                clientFrames += frame
                uplink.put(frame)
                if (frame["type"] == JsonPrimitive("open") && frame["endpoint"] == JsonPrimitive("\$events")) {
                    eventStreamId = frame.getValue("streamId").jsonPrimitive.content
                    sendEvent(jsonObject("""{"type":"ready","clientId":"stream-client","host":"test-host"}"""))
                }
            }
        }

        override fun close() {
            scope.cancel()
            stream.close()
            runBlocking { running?.cancelAndJoin() }
            api.shutdownEventStreams()
            server.shutdown()
        }
    }

    companion object {
        private const val CLIENT_ID = "stream-client"
        private fun jsonObject(text: String): JsonObject = DshJson.parseToJsonElement(text).jsonObject
    }
}
