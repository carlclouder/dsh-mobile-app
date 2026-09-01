package dev.dshmobile.ui

import dev.dshmobile.model.RespondReceipt

/**
 * 应答回执 → UI 决策（纯函数、无 Android 依赖 → JVM 可单测）。
 *
 * 背景（bug：会话页面永久卡住的第二路径）：对话页内联审批/提问卡应答原先无条件乐观移除
 * 且丢弃回执——传输失败时卡片静默消失、agent 永远等不到回答，页面死锁。
 *
 * 三态语义与通知路径 ApprovalReceiver 对齐：
 * - Accepted：服务端已受理 → 静默维持卡片移除；
 * - NotPending：已在别处处理（如通知栏已答）→ 维持移除 + 提示"已在别处处理"；
 * - Transport/BadResponse/Malformed：应答未送达或被拒 → 恢复卡片让用户可重试/改答案再交。
 *   （BadResponse 也恢复而非静默：宁可让用户改答案重试，也不允许"卡片消失 + agent 永等"的死局。）
 */
object RespondReceiptPolicy {

    /** 回执决策：ACCEPTED=受理静默 | ALREADY_HANDLED=别处已处理（提示后维持移除）| RETRY=失败恢复卡片重试。 */
    enum class Decision { ACCEPTED, ALREADY_HANDLED, RETRY }

    fun decide(receipt: RespondReceipt): Decision = when (receipt) {
        is RespondReceipt.Accepted -> Decision.ACCEPTED
        is RespondReceipt.NotPending -> Decision.ALREADY_HANDLED
        is RespondReceipt.BadResponse,
        is RespondReceipt.Transport,
        is RespondReceipt.Malformed,
        -> Decision.RETRY
    }
}
