package dev.dshmobile

import dev.dshmobile.service.SessionEventDeduper
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * session/event seq 水位去重回归（bug：流式输出叠词——传输/重连层偶发重复投递同一帧，
 * chunk 文本追加不幂等导致每个词重复两次）。
 *
 * 手写对照组：宿主 seq 严格递增全唯一（24892 事件实证），水位语义 =
 * 首见放行、重复/迟到/回退丢弃、更新放行、无序号帧放行不推进、会话间互不影响。
 */
class SessionEventDeduperTest {

    @Test
    fun `first seen seq is admitted`() {
        val d = SessionEventDeduper()
        assertTrue(d.admit("s1", 100L))
    }

    @Test
    fun `duplicate seq is dropped`() {
        val d = SessionEventDeduper()
        assertTrue(d.admit("s1", 100L))
        // 同帧重复投递（叠词根因场景）：丢弃
        assertFalse(d.admit("s1", 100L))
    }

    @Test
    fun `late or reordered lower seq is dropped`() {
        val d = SessionEventDeduper()
        assertTrue(d.admit("s1", 200L))
        // 迟到/乱序回退的旧帧：丢弃（水位只进不退）
        assertFalse(d.admit("s1", 150L))
        assertFalse(d.admit("s1", 199L))
        // 后续正常帧继续放行
        assertTrue(d.admit("s1", 201L))
    }

    @Test
    fun `zero or negative seq passes through without advancing watermark`() {
        val d = SessionEventDeduper()
        // 无序号帧（畸形/缺 seq）：放行（不吞掉未知帧），但不推进水位
        assertTrue(d.admit("s1", 0L))
        assertTrue(d.admit("s1", -5L))
        // 水位未被 0/-5 推进：首个正常 seq 照常放行
        assertTrue(d.admit("s1", 100L))
    }

    @Test
    fun `watermarks are independent per session`() {
        val d = SessionEventDeduper()
        assertTrue(d.admit("s1", 100L))
        // 另一会话同 seq：各会话独立水位，放行
        assertTrue(d.admit("s2", 100L))
        assertFalse(d.admit("s1", 100L))
        assertFalse(d.admit("s2", 100L))
    }
}
