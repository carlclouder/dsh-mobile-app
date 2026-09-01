package dev.dshmobile

import dev.dshmobile.model.ConversationEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 会话事件解析单测：样例 JSON 全部取自 session.history 真实事件（2026-08-25 CDP 抓取）。
 */
class ConversationEventTest {

    private fun parse(type: String, seq: Long, json: String): ConversationEvent =
        ConversationEvent.parse(type, seq, Json.parseToJsonElement(json).jsonObject)

    @Test
    fun `user message extracts text`() {
        val ev = parse("user/message", 54725, """
            {"content":[{"type":"text","text":"暂停先写 进度文件"}],
             "source":{"kind":"user","rpcId":"06734424-53ab-4b4b-ba0d-d381373531f7","clientTimeZone":"Asia/Shanghai"},
             "role":"user","id":"a9ce2d95-afcd-49d2-91ff-a3fff7840cff"}
        """.trimIndent())
        val u = assertIs<ConversationEvent.UserMessage>(ev)
        assertEquals("a9ce2d95-afcd-49d2-91ff-a3fff7840cff", u.key)
        assertEquals("暂停先写 进度文件", u.text)
    }

    @Test
    fun `assistant message extracts text and tool calls`() {
        val ev = parse("assistant/message", 54719, """
            {"turn":6,"step":14,"message":{"role":"assistant","content":[
              {"type":"reasoning","text":"JDK download failed from both sources."},
              {"type":"text","text":"JDK 两个源都失败了。"},
              {"type":"tool-call","id":"call_2afd039ad37345208ebd65e6","name":"pwsh",
               "arguments":"{\"command\":\"echo hi\"}"}
            ]}}
        """.trimIndent())
        val a = assertIs<ConversationEvent.AssistantMessage>(ev)
        assertTrue(a.text.contains("JDK 两个源"))
        assertEquals("JDK download failed from both sources.", a.reasoning)
        assertEquals(1, a.toolCalls.size)
        assertEquals("pwsh", a.toolCalls[0].name)
        assertEquals("call_2afd039ad37345208ebd65e6", a.toolCalls[0].id)
    }

    @Test
    fun `assistant message without content has empty text`() {
        val ev = parse("assistant/message", 54600, """{"turn":1,"step":1,"message":{"role":"assistant","content":[]}}""")
        val a = assertIs<ConversationEvent.AssistantMessage>(ev)
        assertTrue(a.text.isEmpty())
        assertTrue(a.toolCalls.isEmpty())
    }

    @Test
    fun `tool result extracts output joining content lines`() {
        val ev = parse("tool/result", 54721, """
            {"turn":6,"step":14,"message":{"source":{"kind":"tool","callId":"call_x"},"content":[
              {"type":"tool-result","toolCallId":"call_x","content":["@{type=text; text=line1}","line2"],"isError":false}
            ]}}
        """.trimIndent())
        val t = assertIs<ConversationEvent.ToolResult>(ev)
        assertTrue(t.output.contains("line1") && t.output.contains("line2"))
        assertEquals(false, t.isError)
    }

    // ---- 工具结果类型化块形状（修复"工具结果空内容"：真帧取自 session.history 2026-08-29 实测） ----

    @Test
    fun `tool result parses typed text blocks from real frame`() {
        // 宿主真形状：content 内层是 [{type:"text",text}] 块数组（本机 45/45 全为此形状），不再是字符串数组
        val ev = parse("tool/result", 39500, """
            {"turn":2,"step":60,"message":{"source":{"kind":"tool","callId":"call_abc"},"content":[
              {"type":"tool-result","toolCallId":"call_abc",
               "content":[{"type":"text","text":"started background job pwsh-8"},{"type":"text","text":"second line"}],
               "isError":false}
            ]}}
        """.trimIndent())
        val t = assertIs<ConversationEvent.ToolResult>(ev)
        assertTrue(t.output.contains("started background job pwsh-8"))
        assertTrue(t.output.contains("second line"))
    }

    @Test
    fun `tool result typed blocks with image and unknown get placeholders`() {
        // 图片块/未知块不丢弃：给占位，保证结果卡不空
        val ev = parse("tool/result", 39501, """
            {"turn":2,"step":61,"message":{"source":{"kind":"tool","callId":"call_img"},"content":[
              {"type":"tool-result","toolCallId":"call_img",
               "content":[{"type":"image","ref":"att-1"},{"type":"audio","len":5},{"type":"text","text":"ok"}]}
            ]}}
        """.trimIndent())
        val t = assertIs<ConversationEvent.ToolResult>(ev)
        assertTrue(t.output.contains("[图片]"))
        assertTrue(t.output.contains("[块:audio]"))
        assertTrue(t.output.contains("ok"))
    }

    @Test
    fun `tool result empty typed content yields blank output`() {
        // 真空输出（content:[]）→ 空串（UI 层不再渲染空卡）
        val ev = parse("tool/result", 39502, """
            {"turn":2,"step":62,"message":{"source":{"kind":"tool","callId":"call_e"},"content":[
              {"type":"tool-result","toolCallId":"call_e","content":[],"isError":false}
            ]}}
        """.trimIndent())
        val t = assertIs<ConversationEvent.ToolResult>(ev)
        assertTrue(t.output.isEmpty())
    }

    @Test
    fun `tool result malformed inner content does not crash and stays blank`() {
        // 内层 content 非数组非字符串（畸形帧）→ 空串不抛异常（容错回归）
        val ev = parse("tool/result", 39503, """
            {"turn":2,"step":63,"message":{"source":{"kind":"tool","callId":"call_m"},"content":[
              {"type":"tool-result","toolCallId":"call_m","content":{"unexpected":"shape"}}
            ]}}
        """.trimIndent())
        val t = assertIs<ConversationEvent.ToolResult>(ev)
        assertTrue(t.output.isEmpty())
    }

    @Test
    fun `turn markers parse`() {
        assertIs<ConversationEvent.TurnStart>(parse("turn/start", 55316, """{"turn":8}"""))
        val end = parse("turn/end", 55314, """{"turn":7,"reason":{"kind":"error","error":{"message":"429: limit"}}}""")
        val te = assertIs<ConversationEvent.TurnEnd>(end)
        assertEquals(7, te.turn)
        assertEquals("429: limit", te.reason)
    }

    @Test
    fun `unknown type yields Unknown`() {
        val ev = parse("some/future", 1, """{"x":1}""")
        assertIs<ConversationEvent.Unknown>(ev)
    }

    @Test
    fun `malformed data does not crash`() {
        // data 非预期结构（content 缺失/类型错）→ 空文本或 Unknown，不抛异常
        val user = parse("user/message", 2, """{"foo":"bar"}""")
        assertIs<ConversationEvent.UserMessage>(user)
        assertTrue((user as ConversationEvent.UserMessage).text.isEmpty())
        val assistant = parse("assistant/message", 3, """{"content":"not-an-array"}""")
        assertIs<ConversationEvent.AssistantMessage>(assistant)
    }

    // ---- 上下文注入分类（source.kind != "user" → ContextInjection，只显示来源一行） ----

    @Test
    fun `context injection agent instructions extracts deduped paths as label`() {
        // 结构对照生产端 dsh-agent-instructions/lib/index.js L755-767：source{kind,form,changes[{action,scope,path}]}
        val ev = parse("user/message", 100, """
            {"content":[{"type":"text","text":"<system-reminder>工作区指令正文 AGENTS 内容</system-reminder>"}],
             "source":{"kind":"agent-instructions","form":"instructions",
                       "changes":[{"action":"set","scope":"project","path":"D:/ws/AGENTS.md"},
                                  {"action":"replace","scope":"project","path":"D:/ws/AGENTS.md"}]},
             "role":"user","id":"inj-1"}
        """.trimIndent())
        val c = assertIs<ConversationEvent.ContextInjection>(ev)
        assertEquals("inject", c.role)
        assertEquals("D:/ws/AGENTS.md", c.label)   // 重复 path 去重保序
        assertTrue(c.text.contains("AGENTS"))       // 正文保留，供展开显示
    }

    @Test
    fun `context injection plugin source uses plugin name as label`() {
        val ev = parse("user/message", 101, """
            {"content":[{"type":"text","text":"baseline"}],
             "source":{"kind":"plugin","plugin":"agent-instructions"},"id":"inj-2"}
        """.trimIndent())
        val c = assertIs<ConversationEvent.ContextInjection>(ev)
        assertEquals("inject", c.role)
        assertEquals("agent-instructions", c.label)
    }

    @Test
    fun `context injection skill invocation uses skill name as label`() {
        val ev = parse("user/message", 102, """
            {"content":[{"type":"text","text":"skill ctx"}],
             "source":{"kind":"skill-invocation","name":"ashare-analyst"},"id":"inj-3"}
        """.trimIndent())
        val c = assertIs<ConversationEvent.ContextInjection>(ev)
        assertEquals("ashare-analyst", c.label)
        assertEquals("inject", c.role)
    }

    @Test
    fun `context injection session reference is recall with joined labels`() {
        val ev = parse("user/message", 103, """
            {"content":[{"type":"text","text":"召回"}],
             "source":{"kind":"session-reference","references":[{"label":"旧会话A"},{"label":"旧会话B"},{"label":"旧会话A"}]},
             "id":"inj-4"}
        """.trimIndent())
        val c = assertIs<ConversationEvent.ContextInjection>(ev)
        assertEquals("recall", c.role)
        assertEquals("旧会话A, 旧会话B", c.label)
    }

    @Test
    fun `user message with user source stays user message`() {
        // source.kind == "user" → 仍按普通用户消息（回归锁定）
        val ev = parse("user/message", 104, """
            {"content":[{"type":"text","text":"正常输入"}],
             "source":{"kind":"user","rpcId":"r"},"id":"u-1"}
        """.trimIndent())
        val u = assertIs<ConversationEvent.UserMessage>(ev)
        assertEquals("正常输入", u.text)
    }

    @Test
    fun `context injection with source but no kind degrades to inject with null label`() {
        // source 存在但读不到 kind：按 WebUI 语义归为注入（role=inject，label 降级为 null）
        val ev = parse("user/message", 105, """
            {"content":[{"type":"text","text":"x"}],"source":{"foo":1},"id":"inj-5"}
        """.trimIndent())
        val c = assertIs<ConversationEvent.ContextInjection>(ev)
        assertEquals("inject", c.role)
        assertEquals(null, c.label)
    }

    @Test
    fun `context injection nested message source is also detected`() {
        // data.message.source 嵌套形态（与 extractText 的回退次序一致）
        val ev = parse("user/message", 106, """
            {"message":{"content":[{"type":"text","text":"嵌套"}],
                        "source":{"kind":"plugin","plugin":"compact"}},"id":"inj-6"}
        """.trimIndent())
        val c = assertIs<ConversationEvent.ContextInjection>(ev)
        assertEquals("compact", c.label)
    }

    @Test
    fun `context injection empty references falls back to kind as label`() {
        // references/changes 数组缺失或全不可读时，WebUI 降级为 kind 本身（joined ?? kind），不返回 null——
        // 本例 session-reference 无 references，label 应为 kind 字符串
        val ev = parse("user/message", 107, """
            {"content":[{"type":"text","text":"x"}],
             "source":{"kind":"session-reference"},"id":"inj-7"}
        """.trimIndent())
        val c = assertIs<ConversationEvent.ContextInjection>(ev)
        assertEquals("recall", c.role)
        assertEquals("session-reference", c.label)
    }
}
