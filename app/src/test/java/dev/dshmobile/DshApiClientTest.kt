package dev.dshmobile

import dev.dshmobile.model.ApiResult
import dev.dshmobile.model.RespondReceipt
import dev.dshmobile.network.DshApiClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * DshApiClient 单测（M2）：MockWebServer 走真实 HTTP 回路（真实接口调用，不模拟实现过程）。
 * 覆盖：信封形状（请求侧断言）/ 成功值 / 业务错误 / 网络错误 / 回执三态 / 连通性探测。
 */
class DshApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: DshApiClient

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = DshApiClient(server.url("/").toString().trimEnd('/'))
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    // ---- clientTimeZone 净化（bug：模拟器偏移格式时区被 host 拒 invalid-time-zone → 发送静默失败） ----

    @Test
    fun `time zone iana names pass through`() {
        assertEquals("Asia/Shanghai", client.sanitizedClientTimeZone("Asia/Shanghai"))
        assertEquals("America/New_York", client.sanitizedClientTimeZone("America/New_York"))
        assertEquals("UTC", client.sanitizedClientTimeZone("UTC"))
    }

    @Test
    fun `time zone offset ids degrade to utc`() {
        // 模拟器实测形状：偏移格式 host schema 拒收 → 必须降级 UTC
        assertEquals("UTC", client.sanitizedClientTimeZone("GMT+08:00"))
        assertEquals("UTC", client.sanitizedClientTimeZone("GMT"))
        assertEquals("UTC", client.sanitizedClientTimeZone("UTC+08:00"))
        assertEquals("UTC", client.sanitizedClientTimeZone("EST"))
    }

    // -------------------------------------------------------------------
    // session.list 成功路径 + 请求信封形状断言
    // -------------------------------------------------------------------

    @Test
    fun `sessionList parses items and sends proper envelope`() = runTest {
        // 夹具为 ConvertTo-Json 生成的规范紧凑 JSON（手写花括号曾多一个导致信封解析失败）
        server.enqueue(
            MockResponse().setBody(
                """{"type":"server-response","rpcId":"echo-1","result":{"ok":true,"value":{"items":[{"sessionId":"s1","updatedAt":1755900000.5,"running":true,"blank":false,"cwd":"D:\\proj","projections":{"asOfSeq":3,"values":{"title":"任务A"}}}]}}}"""
            ).setHeader("Content-Type", "application/json")
        )

        val result = client.sessionList()

        val ok = assertIs<ApiResult.Ok<List<dev.dshmobile.model.SessionSummary>>>(result)
        assertEquals(1, ok.value.size)
        assertEquals("s1", ok.value[0].sessionId)
        assertEquals("任务A", ok.value[0].titleOrNull())

        // 请求侧契约断言：路径 / 头 / 信封四字段。
        // content-type 比对逻辑对齐服务端 handler.js L208：取 ';' 前段小写比较，
        // OkHttp 默认追加 "; charset=utf-8" 属合法（服务端同样放行）。
        val recorded = server.takeRequest()
        assertEquals("/api/session/list", recorded.path)
        val mediaType = recorded.getHeader("Content-Type")!!.substringBefore(';').trim().lowercase()
        assertEquals("application/json", mediaType)
        val sentBody = kotlinx.serialization.json.Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("client-request", sentBody["type"]!!.jsonPrimitive.content)
        assertEquals("session/list", sentBody["method"]!!.jsonPrimitive.content)
        assertTrue(sentBody.containsKey("rpcId"))
        assertTrue(sentBody.containsKey("payload"))
    }

    // -------------------------------------------------------------------
    // 错误分支
    // -------------------------------------------------------------------

    @Test
    fun `business error maps to BizError with code`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"type":"server-response","rpcId":"e1","result":{"ok":false,"error":{"code":"session-not-found","message":"gone","details":{"sessionId":"sX"}}}}"""
            ).setHeader("Content-Type", "application/json")
        )

        val result = client.sessionCancel("sX")

        val biz = assertIs<ApiResult.BizError>(result)
        assertEquals("session-not-found", biz.code)
        assertEquals("gone", biz.message)
        assertEquals("/api/session/cancel", server.takeRequest().path)
    }

    @Test
    fun `transport failure maps to NetError`() = runTest {
        // 连接建立后立即断开：模拟隧道瞬断
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val result = client.sessionList()

        assertIs<ApiResult.NetError>(result)
    }

    @Test
    fun `http error status maps to NetError`() = runTest {
        // 网关 502（无业务信封体）
        server.enqueue(MockResponse().setResponseCode(502).setBody("bad gateway"))

        val result = client.workspaceList()

        assertIs<ApiResult.NetError>(result)
    }

    @Test
    fun `ok false without error body maps to NetError`() = runTest {
        // 线上契约不会出现（rpc.schema.js L66-71 error 必填），防御分支守卫（评审 A P2）
        server.enqueue(
            MockResponse().setBody(
                """{"type":"server-response","rpcId":"x","result":{"ok":false}}"""
            ).setHeader("Content-Type", "application/json")
        )

        val result = client.sessionList()

        assertIs<ApiResult.NetError>(result)
    }

    // -------------------------------------------------------------------
    // 评审 B P1-3：传输故障与回执畸形分离
    // -------------------------------------------------------------------

    @Test
    fun `respond transport failure maps to Transport not Malformed`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val receipt = client.respondApproval("rpc-1", "s1", "a1", allow = true)

        val transport = assertIs<RespondReceipt.Transport>(receipt)
        assertTrue(transport.description.isNotBlank())
    }

    @Test
    fun `respond http error maps to Transport with status code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val receipt = client.respondApproval("rpc-1", "s1", "a1", allow = true)

        val transport = assertIs<RespondReceipt.Transport>(receipt)
        assertTrue(transport.description.contains("500"))
    }

    // -------------------------------------------------------------------
    // 评审 B P2-5 / P2-9
    // -------------------------------------------------------------------

    @Test
    fun `blank base url maps call to NetError not crash`() = runTest {
        val badClient = DshApiClient("   ")
        val result = badClient.sessionList()
        assertIs<ApiResult.NetError>(result)
    }

    @Test
    fun `ok true with non-object value maps to NetError`() = runTest {
        // value 为数组 = 协议漂移，不得静默折成空结果（评审 B P2-9）
        server.enqueue(
            MockResponse().setBody(
                """{"type":"server-response","rpcId":"a1","result":{"ok":true,"value":[]}}"""
            ).setHeader("Content-Type", "application/json")
        )
        assertIs<ApiResult.NetError>(client.sessionList())
    }

    // -------------------------------------------------------------------
    // respond 回执
    // -------------------------------------------------------------------

    @Test
    fun `respondApproval sends client-response envelope and parses accepted`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"accepted":true}""")
                .setHeader("Content-Type", "application/json")
        )

        val receipt = client.respondApproval("rpc-9", "s1", "a1", allow = true)

        assertIs<RespondReceipt.Accepted>(receipt)

        val recorded = server.takeRequest()
        assertEquals("/api/respond", recorded.path)
        val sentBody = kotlinx.serialization.json.Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        // client-response 形态：rpcId 回显信封层 id，result.ok=true，value 三字段
        assertEquals("client-response", sentBody["type"]!!.jsonPrimitive.content)
        assertEquals("rpc-9", sentBody["rpcId"]!!.jsonPrimitive.content)
        val resultObj = sentBody["result"]!!.jsonObject
        assertEquals("true", resultObj["ok"]!!.jsonPrimitive.content)
        val value = resultObj["value"]!!.jsonObject
        assertEquals("s1", value["sessionId"]!!.jsonPrimitive.content)
        assertEquals("a1", value["approvalId"]!!.jsonPrimitive.content)
        assertEquals("allowed-once", value["outcome"]!!.jsonPrimitive.content)
    }

    @Test
    fun `respondApproval not-pending receipt is surfaced distinctly`() = runTest {
        // not-pending 单列：调用方据此撤通知而不是重试（设计 §3.1）
        server.enqueue(
            MockResponse().setBody("""{"accepted":false,"reason":"not-pending"}""")
                .setHeader("Content-Type", "application/json")
        )

        val receipt = client.respondApproval("rpc-9", "s1", "a1", allow = false)

        assertIs<RespondReceipt.NotPending>(receipt)
    }

    // -------------------------------------------------------------------
    // respondQuestion（评审 A P1：唯一双层嵌套 value，深层路径锁死）
    // -------------------------------------------------------------------

    @Test
    fun `respondQuestion sends nested answer value with custom field`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"accepted":true}""")
                .setHeader("Content-Type", "application/json")
        )

        val answers = listOf(
            dev.dshmobile.model.QuestionAnswerItem(
                id = "q1", selected = listOf("opt-a"), custom = "备注文字",
            ),
            dev.dshmobile.model.QuestionAnswerItem(id = "q2", selected = listOf("opt-b")),
        )
        val receipt = client.respondQuestion("qrpc-7", "s1", answers)

        assertIs<RespondReceipt.Accepted>(receipt)

        val recorded = server.takeRequest()
        assertEquals("/api/respond", recorded.path)
        val sent = kotlinx.serialization.json.Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("client-response", sent["type"]!!.jsonPrimitive.content)
        assertEquals("qrpc-7", sent["rpcId"]!!.jsonPrimitive.content)
        // 深层路径断言：value.answer.answers[*].{id,selected,custom}（questions.schema.js L9-20）
        val value = sent["result"]!!.jsonObject["value"]!!.jsonObject
        assertEquals("s1", value["sessionId"]!!.jsonPrimitive.content)
        val answersArr = value["answer"]!!.jsonObject["answers"]!!.jsonArray
        assertEquals(2, answersArr.size)
        val first = answersArr[0].jsonObject
        assertEquals("q1", first["id"]!!.jsonPrimitive.content)
        assertEquals("opt-a", first["selected"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("备注文字", first["custom"]!!.jsonPrimitive.content)
        // custom 可选：未提供时不得序列化该键（DshApiClient 条件 put 分支）
        assertTrue(!answersArr[1].jsonObject.containsKey("custom"))
    }

    // -------------------------------------------------------------------
    // sessionCreate（评审 A P2：成功提取 + sessionId 缺失兜底）
    // -------------------------------------------------------------------

    @Test
    fun `sessionCreate extracts sessionId and sends workspaceId payload`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"type":"server-response","rpcId":"c1","result":{"ok":true,"value":{"sessionId":"new-1"}}}"""
            ).setHeader("Content-Type", "application/json")
        )

        val result = client.sessionCreate("ws-1")

        val ok = assertIs<ApiResult.Ok<String>>(result)
        assertEquals("new-1", ok.value)
        val envelopePayload = kotlinx.serialization.json.Json.parseToJsonElement(server.takeRequest().body.readUtf8())
            .jsonObject["payload"]!!.jsonObject
        // 新版契约：payload.args.request（少数端点为 args._request）
        val args = envelopePayload["args"]!!.jsonObject
        val payload = (args["request"] ?: args["_request"])!!.jsonObject
        assertEquals("ws-1", payload["workspaceId"]!!.jsonPrimitive.content)
        // refine 二选一：不得同时携带 cwd
        assertTrue(!payload.containsKey("cwd"))
    }

    @Test
    fun `sessionCreate missing sessionId maps to NetError`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"type":"server-response","rpcId":"c2","result":{"ok":true,"value":{}}}"""
            ).setHeader("Content-Type", "application/json")
        )

        val result = client.sessionCreate("ws-1")

        assertIs<ApiResult.NetError>(result)
    }

    // -------------------------------------------------------------------
    // session.prompt 载荷形状
    // -------------------------------------------------------------------

    @Test
    fun `sessionPrompt sends queue mode text content and timezone`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"type":"server-response","rpcId":"p1","result":{"ok":true,
                    "value":{"accepted":true}}}"""
            ).setHeader("Content-Type", "application/json")
        )

        val result = client.sessionPrompt("s1", "你好")

        assertIs<ApiResult.Ok<Unit>>(result)

        val recorded = server.takeRequest()
        assertEquals("/api/session/prompt", recorded.path)
        val envelopePayload = kotlinx.serialization.json.Json.parseToJsonElement(recorded.body.readUtf8())
            .jsonObject["payload"]!!.jsonObject
        // 新版契约：payload.args.request（少数端点为 args._request）
        val args = envelopePayload["args"]!!.jsonObject
        val payload = (args["request"] ?: args["_request"])!!.jsonObject
        assertEquals("s1", payload["sessionId"]!!.jsonPrimitive.content)
        assertEquals("queue", payload["mode"]!!.jsonPrimitive.content)
        val content = payload["content"]!!.jsonArray
        assertEquals(1, content.size)
        val block = content[0].jsonObject
        assertEquals("text", block["type"]!!.jsonPrimitive.content)
        assertEquals("你好", block["text"]!!.jsonPrimitive.content)
        assertTrue(payload.containsKey("clientTimeZone"))
    }

    // -------------------------------------------------------------------
    // session.updateQueue 插话 action 形状（对齐 WebUI：{kind:"steer"}，无 target）
    // -------------------------------------------------------------------

    @Test
    fun `sessionUpdateQueue steer action sends only kind field`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"type":"server-response","rpcId":"q1","result":{"ok":true,"value":{"accepted":true}}}"""
            ).setHeader("Content-Type", "application/json")
        )

        val result = client.sessionUpdateQueue("s1", "item-1", client.queueSteerAction())

        assertIs<ApiResult.Ok<Unit>>(result)

        val recorded = server.takeRequest()
        assertEquals("/api/session/updateQueue", recorded.path)
        val envelopePayload = kotlinx.serialization.json.Json.parseToJsonElement(recorded.body.readUtf8())
            .jsonObject["payload"]!!.jsonObject
        // 新版契约：payload.args.request（少数端点为 args._request）
        val args = envelopePayload["args"]!!.jsonObject
        val payload = (args["request"] ?: args["_request"])!!.jsonObject
        assertEquals("s1", payload["sessionId"]!!.jsonPrimitive.content)
        assertEquals("item-1", payload["itemId"]!!.jsonPrimitive.content)
        val action = payload["action"]!!.jsonObject
        // host 只读 action.kind；与 WebUI 每行"插话发送"按钮逐字一致，多字段反而偏离基准
        assertEquals(setOf("kind"), action.keys)
        assertEquals("steer", action["kind"]!!.jsonPrimitive.content)
    }

    // -------------------------------------------------------------------
    // 连通性探测
    // -------------------------------------------------------------------

    @Test
    fun `probeConnectivity true on 2xx`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        assertTrue(client.probeConnectivity())
    }

    @Test
    fun `probeConnectivity true on 3xx redirect`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302))
        assertTrue(client.probeConnectivity())
    }

    @Test
    fun `probeConnectivity false on disconnect`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(false, client.probeConnectivity())
    }
}
