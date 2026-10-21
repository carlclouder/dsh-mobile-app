package dev.dshmobile

import dev.dshmobile.model.DshJson
import dev.dshmobile.model.ThinkingPreview
import dev.dshmobile.model.ThinkingPreviewState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 官方快照与实时帧直接交给实际归约器，预期文本和原始索引由夹具手工核对。 */
class ThinkingPreviewStateTest {
    @Test
    fun `紧凑思考快照恢复文本后从原始chunk索引继续`() {
        val state = ThinkingPreviewState()
        val stream = """[
            {"type":"chunk","time":100,"chunk":{"type":"block-start","index":0,"blockType":"reasoning"}},
            {"type":"reasoning-chunks","time0":101,"index":0,"dt":[1],"texts":["第一段","第二段"]}
        ]"""
        // 两条压缩记录包含三个原始 chunk，后续 frame.index 必须从 3 接续。
        assertTrue(state.accept(snapshot(9, attempt("attempt-a", 3, stream))))
        assertEquals(ThinkingPreview("attempt-a:0", "第一段第二段"), state.preview)

        assertTrue(state.accept(chunk(10, 3, """{"type":"reasoning-delta","index":0,"text":"第三段"}""")))

        assertEquals("第一段第二段第三段", state.preview.content)
    }

    @Test
    fun `原始记录和三种压缩段按各自成员数接续而非记录数`() {
        val state = ThinkingPreviewState()
        val stream = """[
            {"type":"chunk","time":100,"chunk":{"type":"block-start","index":0,"blockType":"reasoning"}},
            {"type":"reasoning-chunks","time0":101,"index":0,"dt":[1],"texts":["思考甲","思考乙"]},
            {"type":"chunk","time":103,"chunk":{"type":"block-end","index":0,"block":{"type":"reasoning","text":"完整思考"}}},
            {"type":"chunk","time":104,"chunk":{"type":"block-start","index":1,"blockType":"text"}},
            {"type":"text-chunks","time0":105,"index":1,"dt":[1],"texts":["正文甲","正文乙"]},
            {"type":"chunk","time":107,"chunk":{"type":"block-start","index":2,"blockType":"tool-call"}},
            {"type":"tool-call-chunks","time0":108,"index":2,"dt":[1],"id":"call-1","name":"example","args":["{","}"]}
        ]"""
        // 原始数量为 1+2+1+1+2+1+2=10；尾部工具块不能回显早先思考。
        assertTrue(state.accept(snapshot(20, attempt("attempt-a", 10, stream))))
        assertEquals(ThinkingPreview(), state.preview)
        assertTrue(state.accept(chunk(21, 10, """{"type":"block-start","index":3,"blockType":"reasoning"}""")))
        assertTrue(state.accept(chunk(22, 11, """{"type":"reasoning-delta","index":3,"text":"后续思考"}""")))
        assertEquals(ThinkingPreview("attempt-a:3", "后续思考"), state.preview)
    }

    @Test
    fun `快照原始索引与展开成员数不一致时拒绝并清空`() {
        val state = populatedState()
        val stream = """[{"type":"reasoning-chunks","time0":100,"index":0,"dt":[1],"texts":["甲","乙"]}]"""

        assertFalse(state.accept(snapshot(10, attempt("attempt-b", 1, stream))))

        assertEquals(ThinkingPreview(), state.preview)
        assertFalse(state.hasPreview)
    }

    @Test
    fun `压缩段时间间隔数量不合法时拒绝快照`() {
        val state = ThinkingPreviewState()
        val stream = """[{"type":"reasoning-chunks","time0":100,"index":0,"dt":[],"texts":["甲","乙"]}]"""

        assertFalse(state.accept(snapshot(10, attempt("attempt-b", 2, stream))))

        assertEquals(ThinkingPreview(), state.preview)
    }

    @Test
    fun `没有活动生成的快照立即替换掉旧思考`() {
        val state = populatedState()

        assertTrue(state.accept(snapshot(20)))

        assertEquals(ThinkingPreview(), state.preview)
    }

    @Test
    fun `重连快照恢复新生成且不追加旧内容`() {
        val state = populatedState()
        val stream = """[{"type":"reasoning-chunks","time0":100,"index":2,"dt":[],"texts":["重连后的内容"]}]"""

        assertTrue(state.accept(snapshot(30, attempt("attempt-b", 1, stream))))

        assertEquals(ThinkingPreview("attempt-b:2", "重连后的内容"), state.preview)
    }

    @Test
    fun `较低位置的迟到块结束不能抢占当前思考尾块`() {
        val state = populatedState()
        assertTrue(state.accept(chunk(3, 1, """{"type":"reasoning-delta","index":2,"text":"当前思考"}""")))

        assertTrue(state.accept(chunk(4, 2, """{"type":"block-end","index":0,"block":{"type":"reasoning","text":"旧思考完整内容"}}""")))

        assertEquals(ThinkingPreview("attempt-a:2", "当前思考"), state.preview)
    }

    @Test
    fun `正文开始清空预览且旧思考块结束不使其回弹`() {
        val state = populatedState()
        assertTrue(state.accept(chunk(3, 1, """{"type":"block-start","index":1,"blockType":"text"}""")))
        assertEquals(ThinkingPreview(), state.preview)
        assertTrue(state.accept(chunk(4, 2, """{"type":"text-delta","index":1,"text":"正文"}""")))

        assertTrue(state.accept(chunk(5, 3, """{"type":"block-end","index":0,"block":{"type":"reasoning","text":"迟到的旧思考"}}""")))

        assertEquals(ThinkingPreview(), state.preview)
    }

    @Test
    fun `工具块和后续usage不会重新显示旧思考`() {
        val state = populatedState()
        assertTrue(state.accept(chunk(3, 1, """{"type":"tool-call-delta","index":1,"id":"call-1","name":"example","argumentsDelta":"{}"}""")))
        assertEquals(ThinkingPreview(), state.preview)

        assertTrue(state.accept(chunk(4, 2, """{"type":"usage","usage":{"outputTokens":10}}""")))
        assertTrue(state.accept(chunk(5, 3, """{"type":"finish","reason":{"kind":"tool-calls"}}""")))
        assertEquals(ThinkingPreview(), state.preview)
    }

    @Test
    fun `完整思考块结束替换增量文本而非重复追加`() {
        val state = populatedState()

        assertTrue(state.accept(chunk(3, 1, """{"type":"block-end","index":0,"block":{"type":"reasoning","text":"完整内容"}}""")))

        assertEquals(ThinkingPreview("attempt-a:0", "完整内容"), state.preview)
    }

    @Test
    fun `成功结束和放弃结束都会清空生成预览`() {
        for (outcome in listOf(
            """{"kind":"committed","eventType":"assistant/message","seq":20}""",
            """{"kind":"committed","eventType":"assistant/attempt","seq":20}""",
            """{"kind":"abandoned"}""",
        )) {
            val state = populatedState()

            assertTrue(state.accept(frame("""{"type":"end","attemptId":"attempt-a","revision":3,"index":1,"outcome":$outcome}""")))

            assertEquals(ThinkingPreview(), state.preview)
        }
    }

    @Test
    fun `新step的start换键且不继承上一步文本`() {
        val state = populatedState()
        assertTrue(state.accept(start(3, "attempt-b", step = 2)))
        assertEquals(ThinkingPreview(), state.preview)

        assertTrue(state.accept(chunk(4, 0, """{"type":"reasoning-delta","index":0,"text":"新步骤"}""", "attempt-b")))

        assertEquals(ThinkingPreview("attempt-b:0", "新步骤"), state.preview)
    }

    @Test
    fun `版本缺口清空后必须通过新快照恢复`() {
        val state = populatedState()

        assertFalse(state.accept(chunk(4, 1, """{"type":"reasoning-delta","index":0,"text":"缺失一帧"}""")))
        assertEquals(ThinkingPreview(), state.preview)
        assertFalse(state.accept(start(1)))
        assertTrue(state.accept(snapshot(40)))
        assertTrue(state.accept(start(41, "attempt-new")))
        assertTrue(state.accept(chunk(42, 0, """{"type":"reasoning-delta","index":0,"text":"恢复内容"}""", "attempt-new")))
        assertEquals("恢复内容", state.preview.content)
    }

    @Test
    fun `宿主版本归零和重复帧均不能被静默忽略`() {
        for (revision in listOf(1L, 2L)) {
            val state = populatedState()

            assertFalse(state.accept(start(revision, "attempt-reset")))

            assertEquals(ThinkingPreview(), state.preview)
        }
    }

    @Test
    fun `原始chunk索引缺口和生成身份不符时清空`() {
        for (badChunk in listOf(
            chunk(3, 2, """{"type":"reasoning-delta","index":0,"text":"索引缺口"}"""),
            chunk(3, 1, """{"type":"reasoning-delta","index":0,"text":"另一个生成"}""", "attempt-other"),
        )) {
            val state = populatedState()

            assertFalse(state.accept(badChunk))

            assertEquals(ThinkingPreview(), state.preview)
        }
    }

    @Test
    fun `持久化事件不插入或覆盖临时预览内容`() {
        val state = populatedState()

        assertTrue(state.accept(jsonObject("""{"type":"event","event":{"type":"assistant/message","seq":15,"data":{}}}""")))

        assertEquals(ThinkingPreview("attempt-a:0", "旧思考"), state.preview)
    }

    private fun populatedState(): ThinkingPreviewState = ThinkingPreviewState().also { state ->
        assertTrue(state.accept(snapshot(0)))
        assertTrue(state.accept(start(1)))
        assertTrue(state.accept(chunk(2, 0, """{"type":"reasoning-delta","index":0,"text":"旧思考"}""")))
    }

    private fun snapshot(revision: Long, attempt: JsonObject? = null): JsonObject = buildJsonObject {
        put("type", "snapshot")
        put("assistantStream", buildJsonObject {
            put("revision", revision)
            if (attempt != null) put("activeAttempt", attempt)
        })
    }

    private fun attempt(id: String, nextIndex: Int, stream: String): JsonObject = jsonObject(
        """{"attemptId":"$id","startedAfterSeq":10,"turn":1,"step":1,"nextIndex":$nextIndex,"stream":$stream}""",
    )

    private fun start(revision: Long, id: String = "attempt-a", step: Int = 1): JsonObject = frame(
        """{"type":"start","attemptId":"$id","revision":$revision,"startedAfterSeq":10,"turn":1,"step":$step}""",
    )

    private fun chunk(revision: Long, index: Int, chunk: String, id: String = "attempt-a"): JsonObject = frame(
        """{"type":"chunk","attemptId":"$id","revision":$revision,"index":$index,"time":100,"chunk":$chunk}""",
    )

    private fun frame(frame: String): JsonObject = buildJsonObject {
        put("type", "assistant-stream")
        put("frame", jsonObject(frame))
    }

    private fun jsonObject(text: String): JsonObject = DshJson.parseToJsonElement(text) as JsonObject
}
