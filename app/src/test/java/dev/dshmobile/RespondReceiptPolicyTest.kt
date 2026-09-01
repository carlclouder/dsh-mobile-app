package dev.dshmobile

import dev.dshmobile.model.RespondReceipt
import dev.dshmobile.ui.RespondReceiptPolicy
import dev.dshmobile.ui.RespondReceiptPolicy.Decision
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 应答回执决策回归（bug：审批/提问应答回执被丢弃 + 无条件乐观移除卡片——传输失败时卡片
 * 静默消失、agent 永远等不到回答，会话页面永久卡住）。
 *
 * 手写对照组：五态回执逐一映射期望决策（Accepted→受理静默 / NotPending→别处已处理提示 /
 * Transport·BadResponse·Malformed→恢复卡片重试），覆盖 decide 全分支。
 */
class RespondReceiptPolicyTest {

    @Test
    fun `accepted keeps card dismissed silently`() {
        assertEquals(Decision.ACCEPTED, RespondReceiptPolicy.decide(RespondReceipt.Accepted))
    }

    @Test
    fun `not pending means already handled elsewhere`() {
        assertEquals(
            Decision.ALREADY_HANDLED,
            RespondReceiptPolicy.decide(RespondReceipt.NotPending("not-pending")),
        )
    }

    @Test
    fun `transport failure restores card for retry`() {
        assertEquals(
            Decision.RETRY,
            RespondReceiptPolicy.decide(RespondReceipt.Transport("HTTP 502: bad gateway")),
        )
    }

    @Test
    fun `bad response restores card for retry`() {
        assertEquals(
            Decision.RETRY,
            RespondReceiptPolicy.decide(RespondReceipt.BadResponse("bad-response")),
        )
    }

    @Test
    fun `malformed receipt restores card for retry`() {
        assertEquals(
            Decision.RETRY,
            RespondReceiptPolicy.decide(RespondReceipt.Malformed("<html>gateway error</html>")),
        )
    }
}
