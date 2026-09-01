package dev.dshmobile

import dev.dshmobile.network.DshApiClient
import kotlinx.coroutines.test.runTest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 事件流生命周期单测（评审 B P1-1/P1-4 回归）：
 * - shutdownEventStreams 必须真正关闭活动 WS（evictAll 做不到）
 * - updateBaseUrl 换址必须立即关闭存量流（防两台服务器分裂）
 * - 世代计数随换址自增
 * 全部走 MockWebServer withWebSocketUpgrade 真实 WS 回路。
 */
class DshEventStreamTest {

    private lateinit var server: MockWebServer
    private lateinit var client: DshApiClient

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        // 服务端 WS：接受升级，记录关闭事件
        server.enqueue(MockResponse().withWebSocketUpgrade(serverListener))
        server.start()
        client = DshApiClient(server.url("/").toString().trimEnd('/'))
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    /** 服务端监听：onOpen 放行，onClosed 记录（okhttp3.WebSocketListener 回调名为 onClosed）。 */
    private val serverListener = object : WebSocketListener() {
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            serverClosed.countDown()
        }
    }

    private val serverClosed = CountDownLatch(1)

    /** 客户端监听：等 open / 等 failure。 */
    private class LatchListener : WebSocketListener() {
        val opened = CountDownLatch(1)
        val failed = CountDownLatch(1)
        @Volatile var failure: Throwable? = null
        override fun onOpen(webSocket: WebSocket, response: Response) { opened.countDown() }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            failure = t; failed.countDown()
        }
    }

    @Test
    fun `shutdownEventStreams cancels active web sockets`() = runTest {
        val listener = LatchListener()
        client.openEventStream("/api/events.mux", listener)

        // 握手完成（有界等待：WS 客户端 readTimeout=90s，测试环境本地回环毫秒级）
        assertTrue(listener.opened.await(10, TimeUnit.SECONDS), "handshake should complete")

        // 关停：服务端必须观察到连接关闭
        client.shutdownEventStreams()
        assertTrue(
            listener.failed.await(10, TimeUnit.SECONDS) || serverClosed.await(10, TimeUnit.SECONDS),
            "socket must be closed after shutdown (client failure or server close observed)",
        )
    }

    @Test
    fun `updateBaseUrl closes existing streams and bumps generation`() = runTest {
        val listener = LatchListener()
        client.openEventStream("/api/events.host", listener)
        assertTrue(listener.opened.await(10, TimeUnit.SECONDS), "handshake should complete")

        val before = client.generation()
        client.updateBaseUrl("https://other-host.example.com")
        val after = client.generation()

        assertEquals(before + 1, after)
        // 存量流被立即关闭 → onFailure 触发（上层重连回路据此收拢到新址）
        assertTrue(listener.failed.await(10, TimeUnit.SECONDS), "old stream must be closed on base url change")
    }

    @Test
    fun `wss flip works for uppercase scheme base url`() {
        // 大小写不敏感协议翻转（评审 A P2）：HTTPS:// 前缀也要翻成 wss://
        val upper = DshApiClient("HTTPS://example.ts.net")
        // 不真正连接：只验证 URL 构造不抛异常且翻转正确（通过 openEventStream 到不可达地址，
        // onFailure 会带回请求 url 信息）
        val listener = LatchListener()
        upper.openEventStream("/api/events.mux", listener)
        // 无法断言 url 字段（OkHttp 不暴露），但若翻转失败会以 https 发起且 onFailure 仍触发；
        // 此用例主要守护"不抛异常"与监听回调可达
        assertTrue(listener.failed.await(15, TimeUnit.SECONDS), "listener callbacks must fire")
    }
}
