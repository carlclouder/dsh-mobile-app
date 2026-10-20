package dev.dshmobile

import dev.dshmobile.model.ApiResult
import dev.dshmobile.model.DshJson
import dev.dshmobile.model.InteractionTarget
import dev.dshmobile.model.QuestionAnswerItem
import dev.dshmobile.model.RespondReceipt
import dev.dshmobile.network.DshApiClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 调用真实 HTTP 客户端，按宿主线上契约核对现代回答地址、载荷与回执。 */
class InteractionApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: DshApiClient

    private val remoteTarget = InteractionTarget.Remote("client-active", "event-approval")
    private val continuedTarget = InteractionTarget.Continued("agent-1", "call-1")
    private val answers = listOf(
        QuestionAnswerItem("q1", listOf("选项甲"), "补充说明"),
        QuestionAnswerItem("q2", listOf("选项乙", "选项丙")),
    )

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = DshApiClient(server.url("/").toString())
    }

    @AfterTest
    fun tearDown() {
        client.shutdownEventStreams()
        server.shutdown()
    }

    @Test
    fun `现代审批允许使用裸字符串和精确命名参数`() = runTest {
        enqueueSuccess()
        client.setInteractionClient(remoteTarget.clientId)

        val receipt = client.respondApproval(
            "legacy-rpc", "session-1", "legacy-approval", true, remoteTarget,
        )

        assertIs<RespondReceipt.Accepted>(receipt)
        assertRequest("\$events/result", jsonObject(
            """{"clientId":"client-active","eventId":"event-approval","outcome":{"kind":"result","value":"allowed-once"}}""",
        ))
    }

    @Test
    fun `现代审批拒绝使用裸字符串`() = runTest {
        enqueueSuccess()
        client.setInteractionClient(remoteTarget.clientId)

        val receipt = client.respondApproval(
            "legacy-rpc", "session-1", "legacy-approval", false, remoteTarget,
        )

        assertIs<RespondReceipt.Accepted>(receipt)
        assertRequest("\$events/result", jsonObject(
            """{"clientId":"client-active","eventId":"event-approval","outcome":{"kind":"result","value":"rejected"}}""",
        ))
    }

    @Test
    fun `现代提问直接提交答案且不携带旧版会话外层`() = runTest {
        enqueueSuccess()
        client.setInteractionClient(remoteTarget.clientId)

        val receipt = client.respondQuestion("legacy-rpc", "session-1", answers, remoteTarget)

        assertIs<RespondReceipt.Accepted>(receipt)
        assertRequest("\$events/result", jsonObject(
            """{"clientId":"client-active","eventId":"event-approval","outcome":{"kind":"result","value":{"answers":[{"id":"q1","selected":["选项甲"],"custom":"补充说明"},{"id":"q2","selected":["选项乙","选项丙"]}]}}}""",
        ))
    }

    @Test
    fun `失效的交互连接不能发送HTTP请求`() = runTest {
        // 即使新流已经就绪，旧卡片的回答地址也不能借用该连接。
        enqueueSuccess()
        client.setInteractionClient("client-new")

        val receipt = client.respondQuestion("legacy-rpc", "session-1", answers, remoteTarget)

        assertIs<RespondReceipt.Transport>(receipt)
        assertFalse(client.isInteractionClientActive(remoteTarget.clientId))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `清空交互连接后不能继续回答`() = runTest {
        enqueueSuccess()
        client.setInteractionClient(remoteTarget.clientId)
        client.setInteractionClient(null)

        val receipt = client.respondApproval("rpc", "session-1", "approval", true, remoteTarget)

        assertIs<RespondReceipt.Transport>(receipt)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `切换服务器立即停用旧目标并允许新目标回答`() = runTest {
        val nextServer = MockWebServer()
        nextServer.start()
        try {
            enqueueSuccess()
            nextServer.enqueue(successResponse())
            client.setInteractionClient(remoteTarget.clientId)
            client.updateBaseUrl(nextServer.url("/").toString())

            val staleReceipt = client.respondApproval(
                "rpc", "session-1", "approval", true, remoteTarget,
            )

            assertIs<RespondReceipt.Transport>(staleReceipt)
            assertFalse(client.isInteractionClientActive(remoteTarget.clientId))
            assertEquals(0, server.requestCount)
            assertEquals(0, nextServer.requestCount)

            client.setInteractionClient("client-new")
            val receipt = client.respondApproval(
                "rpc", "session-1", "approval", true,
                InteractionTarget.Remote("client-new", "event-new"),
            )
            assertIs<RespondReceipt.Accepted>(receipt)
            assertEquals(0, server.requestCount)
            val recorded = assertNotNull(nextServer.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("/api/\$events/result", recorded.path)
            val args = DshJson.parseToJsonElement(recorded.body.readUtf8()).jsonObject
                .getValue("payload").jsonObject.getValue("args").jsonObject
            assertEquals(JsonPrimitive("client-new"), args["clientId"])
            assertEquals(JsonPrimitive("event-new"), args["eventId"])
        } finally {
            nextServer.shutdown()
        }
    }

    @Test
    fun `原始现代调用接受裸布尔且不添加request包装`() = runTest {
        val args = jsonObject("""{"agentId":"agent-1","callId":"call-1"}""")
        for (expected in listOf(true, false)) {
            enqueueSuccess(JsonPrimitive(expected))

            val result = client.callModernRaw("userQuestions/answer", args)

            assertEquals(JsonPrimitive(expected), assertIs<ApiResult.Ok<JsonElement?>>(result).value)
            assertRequest("userQuestions/answer", args)
        }
    }

    @Test
    fun `原始现代调用接受没有value字段的void结果`() = runTest {
        enqueueSuccess()
        val args = jsonObject("""{"clientId":"client-1","eventId":"event-1","outcome":{"kind":"next"}}""")

        val result = client.callModernRaw("\$events/result", args)

        assertNull(assertIs<ApiResult.Ok<JsonElement?>>(result).value)
        assertRequest("\$events/result", args)
    }

    @Test
    fun `原始现代调用接受显式null结果`() = runTest {
        enqueueSuccess(JsonNull)
        val args = jsonObject("""{"request":{"sessionId":"session-1"}}""")

        val result = client.callModernRaw("session/projections", args)

        assertNull(assertIs<ApiResult.Ok<JsonElement?>>(result).value)
        assertRequest("session/projections", args)
    }

    @Test
    fun `稍后回答收到true只确认回答已提交`() = runTest {
        enqueueSuccess(JsonPrimitive(true))

        val receipt = client.respondQuestion("rpc", "session-1", answers, continuedTarget)

        assertIs<RespondReceipt.Accepted>(receipt)
        assertRequest("userQuestions/answer", continuedArgs())
    }

    @Test
    fun `稍后回答绑定当前宿主世代时可以提交`() = runTest {
        enqueueSuccess(JsonPrimitive(true))
        val target = continuedTarget.copy(generation = client.generation())

        val receipt = client.respondQuestion("rpc", "session-1", answers, target)

        assertIs<RespondReceipt.Accepted>(receipt)
        assertRequest("userQuestions/answer", continuedArgs())
    }

    @Test
    fun `换址后绑定旧宿主世代的稍后回答不得发送HTTP`() = runTest {
        enqueueSuccess(JsonPrimitive(true))
        val target = continuedTarget.copy(generation = client.generation())
        client.updateBaseUrl(server.url("/new-host").toString())

        val receipt = client.respondQuestion("rpc", "session-1", answers, target)

        assertIs<RespondReceipt.Transport>(receipt)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `稍后回答收到false明确返回已不待答`() = runTest {
        enqueueSuccess(JsonPrimitive(false))

        val receipt = client.respondQuestion("rpc", "session-1", answers, continuedTarget)

        assertEquals("not-pending", assertIs<RespondReceipt.NotPending>(receipt).rawReason)
        assertRequest("userQuestions/answer", continuedArgs())
    }

    @Test
    fun `稍后回答已排队时保留REPLY_QUEUED且不重发`() = runTest {
        enqueueError("REPLY_QUEUED", "回答已在队列中")

        val receipt = client.respondQuestion("rpc", "session-1", answers, continuedTarget)

        assertEquals("REPLY_QUEUED", assertIs<RespondReceipt.NotPending>(receipt).rawReason)
        assertRequest("userQuestions/answer", continuedArgs())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `稍后回答拒绝把字符串布尔值当作成功`() = runTest {
        for (value in listOf("true", "false")) {
            enqueueSuccess(JsonPrimitive(value))

            val receipt = client.respondQuestion("rpc", "session-1", answers, continuedTarget)

            assertIs<RespondReceipt.Malformed>(receipt)
            assertRequest("userQuestions/answer", continuedArgs())
        }
    }

    @Test
    fun `稍后回答缺少布尔值时返回格式错误`() = runTest {
        enqueueSuccess()

        val receipt = client.respondQuestion("rpc", "session-1", answers, continuedTarget)

        assertIs<RespondReceipt.Malformed>(receipt)
        assertRequest("userQuestions/answer", continuedArgs())
    }

    @Test
    fun `稍后回答在宿主收取请求后断线时不自动重发`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        // 如果误开启自动重试，第二个响应会使结果变成成功，断言同时检查请求总数。
        enqueueSuccess(JsonPrimitive(true))

        val receipt = client.respondQuestion("rpc", "session-1", answers, continuedTarget)

        assertIs<RespondReceipt.Transport>(receipt)
        assertRequest("userQuestions/answer", continuedArgs())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `现代回答HTTP失败保留传输错误且不退回旧接口`() = runTest {
        server.enqueue(MockResponse().setResponseCode(502).setBody("网关不可用"))
        client.setInteractionClient(remoteTarget.clientId)

        val receipt = client.respondApproval("rpc", "session-1", "approval", true, remoteTarget)

        assertTrue(assertIs<RespondReceipt.Transport>(receipt).description.contains("502"))
        assertEquals("/api/\$events/result", server.takeRequest(2, TimeUnit.SECONDS)?.path)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `网关没有透传业务代码时不根据英文消息猜测排队状态`() = runTest {
        enqueueError("gateway/internal", "a reply is already queued for this question")

        val receipt = client.respondQuestion("rpc", "session-1", answers, continuedTarget)

        assertTrue(assertIs<RespondReceipt.BadResponse>(receipt).rawReason.orEmpty().contains("gateway/internal"))
        assertRequest("userQuestions/answer", continuedArgs())
    }

    @Test
    fun `委派及超时拒绝原样提交到现代结果接口`() = runTest {
        client.setInteractionClient(remoteTarget.clientId)
        val outcomes = listOf(
            jsonObject("""{"kind":"next"}"""),
            jsonObject("""{"kind":"rejected","error":{"name":"UserQuestionError","code":"ASK_TIMED_OUT","message":"回答等待时间已结束"}}"""),
        )
        for (outcome in outcomes) {
            enqueueSuccess()

            assertIs<RespondReceipt.Accepted>(client.sendInteractionOutcome(remoteTarget, outcome))

            assertRequest("\$events/result", buildJsonObject {
                put("clientId", remoteTarget.clientId)
                put("eventId", remoteTarget.eventId)
                put("outcome", outcome)
            })
        }
    }

    private fun continuedArgs(): JsonObject = jsonObject(
        """{"agentId":"agent-1","callId":"call-1","answer":{"answers":[{"id":"q1","selected":["选项甲"],"custom":"补充说明"},{"id":"q2","selected":["选项乙","选项丙"]}]}}""",
    )

    private fun jsonObject(text: String): JsonObject = DshJson.parseToJsonElement(text).jsonObject

    private fun enqueueSuccess(value: JsonElement? = null) {
        server.enqueue(successResponse(value))
    }

    private fun successResponse(value: JsonElement? = null): MockResponse {
        val envelope = buildJsonObject {
            put("type", "server-response")
            put("rpcId", "test-response")
            put("result", buildJsonObject {
                put("ok", true)
                if (value != null) put("value", value)
            })
        }
        return MockResponse().setBody(envelope.toString()).setHeader("Content-Type", "application/json")
    }

    private fun enqueueError(code: String, message: String) {
        val envelope = buildJsonObject {
            put("type", "server-response")
            put("rpcId", "test-response")
            put("result", buildJsonObject {
                put("ok", false)
                put("error", buildJsonObject {
                    put("code", code)
                    put("message", message)
                    put("details", JsonObject(emptyMap()))
                })
            })
        }
        server.enqueue(MockResponse().setBody(envelope.toString()).setHeader("Content-Type", "application/json"))
    }

    private fun assertRequest(method: String, expectedArgs: JsonObject) {
        val recorded = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS), "必须实际发出HTTP请求")
        assertEquals("POST", recorded.method)
        assertEquals("/api/$method", recorded.path)
        assertEquals("application/json", recorded.getHeader("Content-Type")?.substringBefore(';'))
        val envelope = jsonObject(recorded.body.readUtf8())
        assertEquals(setOf("type", "rpcId", "method", "payload"), envelope.keys)
        assertEquals(JsonPrimitive("client-request"), envelope["type"])
        assertEquals(JsonPrimitive(method), envelope["method"])
        assertTrue(envelope.getValue("rpcId").jsonPrimitive.content.isNotBlank())
        assertEquals(buildJsonObject { put("args", expectedArgs) }, envelope["payload"])
    }
}
