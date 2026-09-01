package dev.dshmobile

import dev.dshmobile.model.FrameParser
import dev.dshmobile.model.HostFrame
import dev.dshmobile.model.MuxFrame
import dev.dshmobile.model.RespondReceipt
import dev.dshmobile.model.ServerResponseEnvelope
import dev.dshmobile.model.SessionSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 协议反序列化单测（M2）。
 *
 * 样例 JSON 全部按 dsh-host-apiproxy zod schema 源码手工构造（字段名/可空性一一对照），
 * 锁死线上契约：服务端字段变更会在本测试立刻红。
 */
class ModelParsingTest {

    // -------------------------------------------------------------------
    // 信封
    // -------------------------------------------------------------------

    @Test
    fun `envelope parses with rpcId at envelope layer`() {
        val text = """
            {"type":"server-request","rpcId":"env-42","method":"approval/requested",
             "payload":{"type":"approval/requested","sessionId":"s1","approvalId":"a1",
                        "toolName":"pwsh","reason":"run installer"}}
        """.trimIndent()
        val envelope = FrameParser.parseEnvelope(text)
        assertNotNull(envelope)
        // rpcId 必须取自信封层而非帧内（设计 §3.1 P2 修正点）
        assertEquals("env-42", envelope.rpcId)
        assertEquals("approval/requested", envelope.method)
        val frame = FrameParser.parseMuxFrame(envelope.payload)
        val requested = assertIs<MuxFrame.ApprovalRequested>(frame)
        assertEquals("a1", requested.approvalId)
        assertEquals("pwsh", requested.toolName)
        assertEquals("run installer", requested.reason)
    }

    @Test
    fun `malformed envelope text returns null instead of throwing`() {
        assertNull(FrameParser.parseEnvelope("not json at all"))
        // 缺 rpcId 字段（信封必需项）→ 丢弃
        assertNull(FrameParser.parseEnvelope("""{"type":"server-request","method":"x","payload":{}}"""))
        // payload 非对象 → 丢弃
        assertNull(FrameParser.parseEnvelope("""{"type":"server-request","rpcId":"1","method":"x","payload":"str"}"""))
    }

    // -------------------------------------------------------------------
    // server-response 信封
    // -------------------------------------------------------------------

    @Test
    fun `server response ok carries value`() {
        val envelope = FrameParser.parseServerResponse(
            """{"type":"server-response","rpcId":"r1","result":{"ok":true,"value":{"accepted":true}}}"""
        )
        assertNotNull(envelope)
        assertTrue(envelope.result.ok)
        assertEquals(true, envelope.result.value!!.jsonObject["accepted"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `server response void call omits value`() {
        // void 业务结果序列化时无 value 字段（rpc.schema.js L73-75 注释）
        val envelope = FrameParser.parseServerResponse(
            """{"type":"server-response","rpcId":"r2","result":{"ok":true}}"""
        )
        assertNotNull(envelope)
        assertTrue(envelope.result.ok)
        assertNull(envelope.result.value)
    }

    @Test
    fun `server response error branch parses code and message`() {
        val envelope = FrameParser.parseServerResponse(
            """{"type":"server-response","rpcId":"r3",
                "result":{"ok":false,"error":{"code":"session-not-found",
                "message":"no such session","details":{"sessionId":"sX"}}}}"""
        )
        assertNotNull(envelope)
        val error = assertNotNull(envelope.result.error)
        assertEquals("session-not-found", error.code)
        assertEquals("no such session", error.message)
        assertEquals("sX", error.details["sessionId"]!!.jsonPrimitive.content)
    }

    // -------------------------------------------------------------------
    // mux 帧全类型
    // -------------------------------------------------------------------

    @Test
    fun `all mux frame types parse`() {
        // session/event：event 内部保持宽 JSON 透传
        val event = FrameParser.parseMuxFrame(json("""
            {"type":"session/event","sessionId":"s1",
             "event":{"type":"message/start","seq":3,"time":1755900000.5,"data":{}}}"""))
        assertIs<MuxFrame.SessionEvent>(event)

        val subscribed = FrameParser.parseMuxFrame(json(
            """{"type":"session/subscribed","sessionId":"s1","lastSeq":17}"""))
        assertIs<MuxFrame.SessionSubscribed>(subscribed)
        assertEquals(17L, subscribed.lastSeq)

        val resolved = FrameParser.parseMuxFrame(json(
            """{"type":"approval/resolved","sessionId":"s1","approvalId":"a1","outcome":"rejected"}"""))
        assertIs<MuxFrame.ApprovalResolved>(resolved)
        assertEquals("rejected", resolved.outcome)

        // question/requested：问题条目字段全展开
        val question = FrameParser.parseMuxFrame(json("""
            {"type":"question/requested","sessionId":"s1","questions":[
              {"id":"q1","question":"继续吗？","header":"确认","detail":"说明文字",
               "options":[{"label":"继续","description":"执行下一步"},{"label":"停止"}],
               "multiSelect":false}]}"""))
        val q = assertIs<MuxFrame.QuestionRequested>(question)
        assertEquals(1, q.questions.size)
        assertEquals("q1", q.questions[0].id)
        assertEquals(2, q.questions[0].options.size)
        assertEquals("执行下一步", q.questions[0].options[0].description)

        val qResolved = FrameParser.parseMuxFrame(json(
            """{"type":"question/resolved","sessionId":"s1","questionRpcId":"qr9","outcome":"answered"}"""))
        assertIs<MuxFrame.QuestionResolved>(qResolved)
        assertEquals("qr9", qResolved.questionRpcId)

        val queue = FrameParser.parseMuxFrame(json(
            """{"type":"session/queue","sessionId":"s1","items":[]}"""))
        assertIs<MuxFrame.SessionQueue>(queue)

        val jobs = FrameParser.parseMuxFrame(json(
            """{"type":"session/jobs","sessionId":"s1","jobs":[]}"""))
        assertIs<MuxFrame.SessionJobs>(jobs)

        val projection = FrameParser.parseMuxFrame(json(
            """{"type":"session/projection","sessionId":"s1","key":"title","value":"修BUG","seq":9}"""))
        val proj = assertIs<MuxFrame.SessionProjection>(projection)
        assertEquals("title", proj.key)
        assertEquals("修BUG", proj.value.jsonPrimitive.content)
        assertEquals(9L, proj.seq)

        val streamError = FrameParser.parseMuxFrame(json(
            """{"type":"stream/error","error":{"code":"internal","message":"boom","details":{}}}"""))
        assertIs<MuxFrame.StreamError>(streamError)
    }

    @Test
    fun `unknown mux frame type yields Unknown not crash`() {
        // 服务端未来扩展帧类型：容错为 Unknown，由上层记日志忽略
        val frame = FrameParser.parseMuxFrame(json("""{"type":"future/frame","x":1}"""))
        val unknown = assertIs<MuxFrame.Unknown>(frame)
        assertEquals("future/frame", unknown.type)
    }

    @Test
    fun `known mux type with missing required field yields null`() {
        // approval/requested 缺 approvalId → 丢弃（对齐浏览器 drop 策略）
        assertNull(FrameParser.parseMuxFrame(json(
            """{"type":"approval/requested","sessionId":"s1","toolName":"t"}""")))
    }

    // -------------------------------------------------------------------
    // host 帧全类型
    // -------------------------------------------------------------------

    @Test
    fun `all host frame types parse`() {
        val added = FrameParser.parseHostFrame(json("""
            {"type":"host/session-added","sessionId":"s2","blank":false,
             "origin":"subagent","cwd":"D:\\proj"}"""))
        val a = assertIs<HostFrame.SessionAdded>(added)
        assertEquals("subagent", a.origin)
        assertEquals("D:\\proj", a.cwd)

        assertIs<HostFrame.SessionRemoved>(FrameParser.parseHostFrame(json(
            """{"type":"host/session-removed","sessionId":"s2"}""")))

        // host/session-status：回合完成通知的核心信号
        val status = assertIs<HostFrame.SessionStatus>(FrameParser.parseHostFrame(json(
            """{"type":"host/session-status","sessionId":"s2","running":false}""")))
        assertEquals(false, status.running)

        assertIs<HostFrame.AgentError>(FrameParser.parseHostFrame(json(
            """{"type":"host/agent-error","sessionId":"s2","message":"crashed"}""")))

        assertIs<HostFrame.WorkspaceChanged>(FrameParser.parseHostFrame(json("""
            {"type":"host/workspace-changed","workspace":{"workspaceId":"w1","path":"D:\\p",
             "title":"P","sessionIds":["s2"],"createdAt":"2026-08-01T00:00:00Z",
             "updatedAt":"2026-08-02T00:00:00Z"}}""")))

        assertIs<HostFrame.WorkspaceRemoved>(FrameParser.parseHostFrame(json(
            """{"type":"host/workspace-removed","workspaceId":"w1"}""")))

        assertIs<HostFrame.WorkspaceOrderChanged>(FrameParser.parseHostFrame(json(
            """{"type":"host/workspace-order-changed","workspaceIds":["w2","w1"]}""")))

        assertIs<HostFrame.ArchivedSessionsChanged>(FrameParser.parseHostFrame(json(
            """{"type":"host/archived-sessions-changed","archivedSessionIds":["s9"]}""")))

        assertIs<HostFrame.RemoteEvent>(FrameParser.parseHostFrame(json(
            """{"type":"host/remote-event","event":"anything","args":[1,"x"]}""")))

        assertIs<HostFrame.StreamError>(FrameParser.parseHostFrame(json(
            """{"type":"stream/error","error":{"code":"internal","message":"x","details":{}}}""")))
    }

    @Test
    fun `unknown host frame type yields Unknown`() {
        val frame = FrameParser.parseHostFrame(json("""{"type":"host/future"}"""))
        assertIs<HostFrame.Unknown>(frame)
    }

    @Test
    fun `known host type with missing required field yields null`() {
        // host/session-status 缺 running → 丢弃（与 mux 侧对称的负路径，评审 A P2）
        assertNull(FrameParser.parseHostFrame(json(
            """{"type":"host/session-status","sessionId":"s1"}""")))
        // host/workspace-changed 缺 workspace → 丢弃
        assertNull(FrameParser.parseHostFrame(json("""{"type":"host/workspace-changed"}""")))
    }

    // -------------------------------------------------------------------
    // session.list 值与标题投影
    // -------------------------------------------------------------------

    @Test
    fun `session summary parses and extracts title projection`() {
        val summaryJson = json("""
            {"sessionId":"s1","updatedAt":1755900000.5,"running":true,"blank":false,
             "cwd":"D:\\projects","futureField":{"x":1},
             "projections":{"asOfSeq":12,
             "values":{"title":"修构建链","blank":true}}}""")
        // 用生产 DshJson（容忍未知字段）解析：夹具带 futureField 验证不会假阴性（评审 A P2）
        val summary = dev.dshmobile.model.DshJson.decodeFromJsonElement(
            SessionSummary.serializer(), summaryJson)
        assertEquals("s1", summary.sessionId)
        assertTrue(summary.running)
        assertEquals("修构建链", summary.titleOrNull())
        assertEquals(12L, summary.projections!!.asOfSeq)
    }

    @Test
    fun `session summary without projections has null title`() {
        val summaryJson = json(
            """{"sessionId":"s2","updatedAt":1.0,"running":false,"blank":true}""")
        val summary = dev.dshmobile.model.DshJson.decodeFromJsonElement(
            SessionSummary.serializer(), summaryJson)
        assertNull(summary.titleOrNull())
    }

    // -------------------------------------------------------------------
    // respond 回执三态
    // -------------------------------------------------------------------

    @Test
    fun `respond receipt three states parse`() {
        assertIs<RespondReceipt.Accepted>(RespondReceipt.parse("""{"accepted":true}"""))
        val notPending = RespondReceipt.parse("""{"accepted":false,"reason":"not-pending"}""")
        assertIs<RespondReceipt.NotPending>(notPending)
        val bad = RespondReceipt.parse("""{"accepted":false,"reason":"bad-response"}""")
        assertIs<RespondReceipt.BadResponse>(bad)
    }

    @Test
    fun `respond receipt malformed body yields Malformed`() {
        assertIs<RespondReceipt.Malformed>(RespondReceipt.parse("<html>502</html>"))
        assertIs<RespondReceipt.Malformed>(RespondReceipt.parse("""{"accepted":false}"""))
    }

    // -------------------------------------------------------------------
    // 敌意输入防御（评审 B P2-8）
    // -------------------------------------------------------------------

    @Test
    fun `deeply nested json yields null instead of crashing`() {
        // 5 万层嵌套：kotlinx 树构建抛 StackOverflowError（Error 非 Exception），
        // 解析器必须归一为 null 而不是让进程崩
        val depth = 50_000
        val hostile = buildString {
            repeat(depth) { append("[") }
            repeat(depth) { append("]") }
        }
        assertNull(FrameParser.parseEnvelope("""{"type":"server-request","rpcId":"1","method":"m","payload":$hostile}"""))
    }

    @Test
    fun `non-primitive title projection yields null title instead of throwing`() {
        val summaryJson = json("""
            {"sessionId":"s1","updatedAt":1.0,"running":false,"blank":false,
             "projections":{"asOfSeq":0,"values":{"title":{"nested":"object"}}}}""")
        val summary = dev.dshmobile.model.DshJson.decodeFromJsonElement(
            SessionSummary.serializer(), summaryJson)
        assertNull(summary.titleOrNull())
    }

    /** 测试工具：字符串 → JsonObject。 */
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
}
