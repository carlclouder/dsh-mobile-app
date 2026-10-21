package dev.dshmobile

import dev.dshmobile.model.DshJson
import dev.dshmobile.model.ThinkingPreview
import dev.dshmobile.network.DshApiClient
import dev.dshmobile.service.DshThinkingStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 真实 WebSocket 验证跟随信封、预览发布及连接生命周期，不替代被测网络实现。 */
class DshThinkingStreamTest {
    @Test
    fun `页面跟随请求精确开启单会话assistantStream`() {
        Harness().use { harness ->
            harness.start()

            val handshake = assertNotNull(harness.handshakes.poll(5, TimeUnit.SECONDS))
            val opened = assertNotNull(harness.openFrames.poll(5, TimeUnit.SECONDS))

            assertEquals("/api/remote.mux", handshake.path)
            assertEquals(setOf("type", "streamId", "endpoint", "payload"), opened.keys)
            assertEquals(JsonPrimitive("open"), opened["type"])
            assertEquals(JsonPrimitive("session/follow"), opened["endpoint"])
            assertTrue(opened.getValue("streamId").jsonPrimitive.content.isNotBlank())
            assertEquals(jsonObject(
                """{"args":{"request":{"address":{"kind":"session","sessionId":"thinking-test"},"assistantStream":true,"maxMessages":1}}}""",
            ), opened["payload"])
            assertEquals(ThinkingPreview(), harness.stream.preview.value)
        }
    }

    @Test
    fun `实际增量合并发布并在正文到达后保持清空`() {
        Harness().use { harness ->
            harness.start()
            harness.send(start(1))
            harness.send(chunk(2, 0, """{"type":"reasoning-delta","index":0,"text":"第一行"}"""))
            harness.send(chunk(3, 1, """{"type":"reasoning-delta","index":0,"text":"\n第二行"}"""))

            val preview = harness.awaitPreview { it.content == "第一行\n第二行" }
            assertEquals("attempt-a:0", preview.key)

            harness.send(chunk(4, 2, """{"type":"block-start","index":1,"blockType":"text"}"""))
            harness.send(chunk(5, 3, """{"type":"block-end","index":0,"block":{"type":"reasoning","text":"迟到的完整思考"}}"""))
            harness.awaitPreview { it == ThinkingPreview() }
            assertNull(harness.previews.poll(180, TimeUnit.MILLISECONDS), "旧块结束和发布计时器不得使思考回弹")
            assertEquals(ThinkingPreview(), harness.stream.preview.value)
        }
    }

    @Test
    fun `生成结束立即清空尚未发布的增量且无旧内容回弹`() {
        Harness().use { harness ->
            harness.start()
            harness.send(start(1))
            harness.send(chunk(2, 0, """{"type":"reasoning-delta","index":0,"text":"等待结束"}"""))
            harness.awaitPreview { it.content == "等待结束" }
            harness.send(chunk(3, 1, """{"type":"reasoning-delta","index":0,"text":"尚未发布"}"""))
            harness.send(frame(
                """{"type":"end","attemptId":"attempt-a","revision":4,"index":2,"outcome":{"kind":"committed","eventType":"assistant/message","seq":20}}""",
            ))

            harness.awaitPreview { it == ThinkingPreview() }

            assertNull(harness.previews.poll(180, TimeUnit.MILLISECONDS), "结束后计时器不能再发布缓存增量")
            assertEquals(ThinkingPreview(), harness.stream.preview.value)
        }
    }

    @Test
    fun `真实断线后重取紧凑快照且不拼接旧连接文本`() {
        Harness().use { harness ->
            harness.start()
            harness.send(start(1))
            harness.send(chunk(2, 0, """{"type":"reasoning-delta","index":0,"text":"旧连接的内容"}"""))
            harness.awaitPreview { it.content == "旧连接的内容" }
            harness.nextSnapshots.put(jsonObject(
                """{"type":"snapshot","assistantStream":{"revision":30,"activeAttempt":{"attemptId":"attempt-new","startedAfterSeq":20,"turn":2,"step":1,"nextIndex":2,"stream":[{"type":"chunk","time":100,"chunk":{"type":"block-start","index":0,"blockType":"reasoning"}},{"type":"reasoning-chunks","time0":101,"index":0,"dt":[],"texts":["重连恢复"]}]}}}""",
            ))
            assertTrue(assertNotNull(harness.currentPeer).socket.close(1000, "验证重连"))
            harness.awaitPreview { it == ThinkingPreview() }

            harness.awaitPeer()
            val recovered = harness.awaitPreview { it.content == "重连恢复" }

            assertEquals("attempt-new:0", recovered.key)
            assertFalse(recovered.content.contains("旧连接"))
            harness.send(chunk(31, 2, """{"type":"reasoning-delta","index":0,"text":"及后续"}""", "attempt-new"))
            harness.awaitPreview { it.content == "重连恢复及后续" }
            assertEquals(2, harness.peers.size)
        }
    }

    @Test
    fun `宿主revision归零会清空并重新订阅而非忽略新生成`() {
        Harness().use { harness ->
            harness.start()
            harness.send(start(1))
            harness.send(chunk(2, 0, """{"type":"reasoning-delta","index":0,"text":"归零前"}"""))
            harness.awaitPreview { it.content == "归零前" }
            harness.nextSnapshots.put(snapshot(100))

            harness.send(start(1, "attempt-reset"))
            harness.awaitPreview { it == ThinkingPreview() }
            harness.awaitPeer()
            harness.send(start(101, "attempt-reset"))
            harness.send(chunk(102, 0, """{"type":"reasoning-delta","index":0,"text":"重新接续"}""", "attempt-reset"))

            assertEquals("attempt-reset:0", harness.awaitPreview { it.content == "重新接续" }.key)
            assertEquals(2, harness.peers.size)
        }
    }

    @Test
    fun `页面关闭使run结束且不再建立新连接`() {
        Harness().use { harness ->
            harness.start()
            harness.send(start(1))
            harness.send(chunk(2, 0, """{"type":"reasoning-delta","index":0,"text":"页面关闭前"}"""))
            harness.awaitPreview { it.content == "页面关闭前" }

            harness.stream.close()
            runBlocking { withTimeout(5_000) { assertNotNull(harness.running).join() } }

            assertEquals(ThinkingPreview(), harness.stream.preview.value)
            assertTrue(assertNotNull(harness.running).isCompleted)
            assertEquals(1, harness.peers.size)
            assertEquals(1, harness.serverRequestCount)
        }
    }

    @Test
    fun `有效快照和生成结束补读历史但finish不触发`() {
        Harness().use { harness ->
            harness.start()
            harness.awaitHistoryRefresh()
            harness.send(start(1))
            harness.send(chunk(2, 0, """{"type":"reasoning-delta","index":0,"text":"真实思考"}"""))
            harness.awaitPreview { it.content == "真实思考" }
            harness.send(chunk(3, 1, """{"type":"finish","reason":{"kind":"tool-calls"}}"""))
            harness.assertNoHistoryRefresh()

            harness.send(frame(
                """{"type":"end","attemptId":"attempt-a","revision":4,"index":2,"outcome":{"kind":"committed","eventType":"assistant/message","seq":20}}""",
            ))
            harness.awaitHistoryRefresh()
            harness.awaitPreview { it == ThinkingPreview() }
            harness.assertNoHistoryRefresh()

            harness.send(start(5, "attempt-abandoned"))
            harness.send(frame(
                """{"type":"end","attemptId":"attempt-abandoned","revision":6,"index":0,"outcome":{"kind":"abandoned"}}""",
            ))
            harness.awaitHistoryRefresh()
            harness.assertNoHistoryRefresh()
        }
    }

    @Test
    fun `旧尝试或索引错误的结束不补读而重连快照会补读`() {
        val invalidEnds = listOf(
            listOf(start(1), start(2, "attempt-new")) to frame(
                """{"type":"end","attemptId":"attempt-a","revision":3,"index":0,"outcome":{"kind":"committed","eventType":"assistant/message","seq":20}}""",
            ),
            listOf(start(1)) to frame(
                """{"type":"end","attemptId":"attempt-a","revision":2,"index":1,"outcome":{"kind":"abandoned"}}""",
            ),
        )
        invalidEnds.forEach { (opening, invalidEnd) ->
            Harness().use { harness ->
                harness.start()
                harness.awaitHistoryRefresh()
                opening.forEach(harness::send)
                harness.nextSnapshots.put(snapshot(20))
                harness.send(invalidEnd)

                harness.awaitPeer()
                harness.awaitHistoryRefresh()
                harness.assertNoHistoryRefresh()
                assertEquals(2, harness.peers.size)
            }
        }
    }

    @Test
    fun `换址后只为新快照补读且旧连接结束不补读`() {
        Harness().use { harness ->
            harness.start()
            harness.awaitHistoryRefresh()
            harness.send(start(1))
            harness.send(chunk(2, 0, """{"type":"reasoning-delta","index":0,"text":"旧主机思考"}"""))
            harness.awaitPreview { it.content == "旧主机思考" }
            val previous = assertNotNull(harness.currentPeer)
            harness.nextSnapshots.put(snapshot(20))

            harness.changeHostGeneration()
            // 旧连接已取消，结束帧即使仍能排入发送缓冲，也不能使旧回调补读新主机。
            previous.socket.send(buildJsonObject {
                put("type", "item")
                put("streamId", previous.streamId)
                put("value", frame(
                    """{"type":"end","attemptId":"attempt-a","revision":3,"index":1,"outcome":{"kind":"committed","eventType":"assistant/message","seq":20}}""",
                ))
            }.toString())
            harness.awaitPeer()
            harness.awaitHistoryRefresh()
            harness.assertNoHistoryRefresh()
            assertEquals(2, harness.peers.size)
        }
    }

    private data class Peer(val socket: WebSocket, val streamId: String)

    private class Harness : AutoCloseable {
        val nextSnapshots = LinkedBlockingQueue<JsonObject>()
        val openFrames = LinkedBlockingQueue<JsonObject>()
        val handshakes = LinkedBlockingQueue<RecordedRequest>()
        val previews = LinkedBlockingQueue<ThinkingPreview>()
        val historyRefreshes = LinkedBlockingQueue<Unit>()
        val peers = CopyOnWriteArrayList<Peer>()
        private val availablePeers = LinkedBlockingQueue<Peer>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val server = MockWebServer()
        private val api: DshApiClient
        val stream: DshThinkingStream
        var running: Job? = null
            private set
        @Volatile var currentPeer: Peer? = null
            private set
        val serverRequestCount: Int get() = server.requestCount

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    handshakes.put(request)
                    return if (request.path == "/api/remote.mux") {
                        MockResponse().withWebSocketUpgrade(serverListener())
                    } else MockResponse().setResponseCode(404)
                }
            }
            server.start()
            api = DshApiClient(server.url("/").toString())
            stream = DshThinkingStream(
                api,
                "thinking-test",
                onHistoryRefresh = { historyRefreshes.put(Unit) },
            )
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                stream.preview.collect { previews.put(it) }
            }
        }

        fun start() {
            running = scope.launch { stream.run() }
            awaitPeer()
        }

        fun awaitPeer(): Peer = assertNotNull(availablePeers.poll(5, TimeUnit.SECONDS), "应重新打开真实会话跟随流")

        fun send(value: JsonObject) {
            val peer = assertNotNull(currentPeer)
            assertTrue(peer.socket.send(buildJsonObject {
                put("type", "item")
                put("streamId", peer.streamId)
                put("value", value)
            }.toString()))
        }

        fun awaitPreview(predicate: (ThinkingPreview) -> Boolean): ThinkingPreview {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (true) {
                val remaining = deadline - System.nanoTime()
                assertTrue(remaining > 0, "未收到预期的思考预览状态")
                val preview = assertNotNull(previews.poll(remaining, TimeUnit.NANOSECONDS))
                if (predicate(preview)) return preview
            }
        }

        fun changeHostGeneration() {
            api.updateBaseUrl(server.url("/").toString())
        }

        fun awaitHistoryRefresh() {
            assertNotNull(historyRefreshes.poll(5, TimeUnit.SECONDS), "有效快照或生成结束应补读历史")
        }

        fun assertNoHistoryRefresh() {
            assertNull(historyRefreshes.poll(180, TimeUnit.MILLISECONDS), "其他片段或无效结束不能重复补读历史")
        }

        private fun serverListener() = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = jsonObject(text)
                if (frame["type"] != JsonPrimitive("open")) return
                openFrames.put(frame)
                val peer = Peer(webSocket, frame.getValue("streamId").jsonPrimitive.content)
                currentPeer = peer
                peers += peer
                val opening = nextSnapshots.poll() ?: snapshot(0)
                // 先把 snapshot 排入同一套接字，再让测试线程发送增量，确保线上帧顺序。
                assertTrue(webSocket.send(buildJsonObject {
                    put("type", "item")
                    put("streamId", peer.streamId)
                    put("value", opening)
                }.toString()))
                availablePeers.put(peer)
            }
        }

        override fun close() {
            stream.close()
            scope.cancel()
            runBlocking { running?.cancelAndJoin() }
            api.shutdownEventStreams()
            server.shutdown()
        }
    }

    companion object {
        private fun snapshot(revision: Long): JsonObject = jsonObject(
            """{"type":"snapshot","assistantStream":{"revision":$revision}}""",
        )

        private fun start(revision: Long, id: String = "attempt-a"): JsonObject = frame(
            """{"type":"start","attemptId":"$id","revision":$revision,"startedAfterSeq":10,"turn":1,"step":1}""",
        )

        private fun chunk(revision: Long, index: Int, chunk: String, id: String = "attempt-a"): JsonObject = frame(
            """{"type":"chunk","attemptId":"$id","revision":$revision,"index":$index,"time":100,"chunk":$chunk}""",
        )

        private fun frame(frame: String): JsonObject = buildJsonObject {
            put("type", "assistant-stream")
            put("frame", jsonObject(frame))
        }

        private fun jsonObject(text: String): JsonObject = DshJson.parseToJsonElement(text).jsonObject
    }
}
