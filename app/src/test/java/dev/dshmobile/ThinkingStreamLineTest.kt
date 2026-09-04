package dev.dshmobile

import dev.dshmobile.ui.thinkingLatestLine
import dev.dshmobile.ui.thinkingSweepBandWidthPx
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 思考折叠行流式摘要（对齐 WebUI ReasoningRow）纯函数回归。
 *
 * 手写对照组：
 * - thinkingLatestLine：trimEnd 后取最后一个 '\n' 之后的片段（无换行取全文），期望值逐例手算；
 * - thinkingSweepBandWidthPx：行宽×0.35 夹在 [60dp,160dp]×density，边界值手算
 *   （density=2 → 区间 [120,320] px；density=1 → [60,160] px）。
 */
class ThinkingStreamLineTest {

    // —— thinkingLatestLine（7 例）——

    @Test
    fun `single line returned as is`() {
        assertEquals("你好世界", thinkingLatestLine("你好世界"))
    }

    @Test
    fun `multi line returns last line`() {
        assertEquals("第二行", thinkingLatestLine("第一行\n第二行"))
    }

    @Test
    fun `trailing newlines trimmed then last segment taken`() {
        // trimEnd 先吃掉尾部三个换行 → 无残留换行 → 取全文 "abc"
        assertEquals("abc", thinkingLatestLine("abc\n\n\n"))
    }

    @Test
    fun `empty string stays empty`() {
        assertEquals("", thinkingLatestLine(""))
    }

    @Test
    fun `leading whitespace of last line preserved like webui`() {
        // WebUI latestLine 只 trimEnd 尾部：trimEnd → "  \n  abc"，末段保留前导两个空格
        assertEquals("  abc", thinkingLatestLine("  \n  abc  "))
    }

    @Test
    fun `crlf line break returns text after lf`() {
        // "a\r\nb"：lastIndexOf('\n') 命中 \n（在 \r 之后），substring 取其后 "b"；\r 属上一行行尾
        assertEquals("b", thinkingLatestLine("a\r\nb"))
    }

    @Test
    fun `long single line not truncated at function level`() {
        val long = "x".repeat(5000)
        assertEquals(long, thinkingLatestLine(long))
    }

    // —— thinkingSweepBandWidthPx（4 例，期望值按 density 手算）——

    @Test
    fun `band width clamped to upper bound`() {
        // density=2：区间 [120,320] px；行宽 1000 → 350 超上限 → 320
        assertEquals(320.0f, thinkingSweepBandWidthPx(rowWidthPx = 1000f, density = 2f))
    }

    @Test
    fun `band width clamped to lower bound`() {
        // density=2：行宽 100 → 35 低下限 → 120
        assertEquals(120.0f, thinkingSweepBandWidthPx(rowWidthPx = 100f, density = 2f))
    }

    @Test
    fun `band width proportional inside bounds`() {
        // density=2：行宽 600 → 210，正好落在 [120,320] 内取原值
        assertEquals(210.0f, thinkingSweepBandWidthPx(rowWidthPx = 600f, density = 2f))
    }

    @Test
    fun `unmeasured zero width falls back to lower bound`() {
        // density=1：行宽 0（未测量）→ 兜底下限 60，保证光带可见
        assertEquals(60.0f, thinkingSweepBandWidthPx(rowWidthPx = 0f, density = 1f))
    }
}
