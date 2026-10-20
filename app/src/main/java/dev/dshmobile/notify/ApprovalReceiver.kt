package dev.dshmobile.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dev.dshmobile.DshRepository
import dev.dshmobile.model.DshJson
import dev.dshmobile.model.InteractionTarget
import dev.dshmobile.model.RespondReceipt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 审批通知按钮接收器（设计 §5.3）：
 * - extras 自包含应答字段；现代地址还须与当前连接核对，进程重启后引导打开 App 恢复
 * - goAsync + 协程：应答是网络调用（10s 级），不能阻塞主线程广播队列（10s ANR 阈值）
 * - 旧回执三态维持原行为；现代成功回执交服务释放活动交互，失败可在 App 重试
 */
class ApprovalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val rpcId = intent.getStringExtra(EXTRA_RPC_ID) ?: return
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val approvalId = intent.getStringExtra(EXTRA_APPROVAL_ID) ?: return
        val allow = intent.getBooleanExtra(EXTRA_OUTCOME_ALLOW, false)

        val appContext = context.applicationContext
        val helper = NotificationHelper(appContext)
        val rawTarget = intent.getStringExtra(EXTRA_TARGET)
        val target = if (rawTarget == null) null else try {
            DshJson.decodeFromString(InteractionTarget.serializer(), rawTarget)
        } catch (_: Exception) {
            helper.showApprovalSendFailed(approvalId, sessionId)
            showToast(appContext, "审批地址无效，请打开 App 恢复交互")
            return
        }
        if (target != null) {
            val current = DshRepository.pendingApprovals.value[sessionId]
                ?.firstOrNull { it.approvalId == approvalId }
            // 通知可能来自旧连接或上次进程；不能向已经失效的 clientId 回答。
            if (target !is InteractionTarget.Remote || current == null || current.target != target || !current.available) {
                helper.showApprovalSendFailed(approvalId, sessionId)
                showToast(appContext, "审批连接已变化，请打开 App 恢复交互")
                return
            }
            respondingTargets.entries.removeIf { (id, address) ->
                DshRepository.pendingApprovals.value.values.none { list ->
                    list.any { it.approvalId == id && it.target == address }
                }
            }
            if (respondingTargets.put(approvalId, target) == target) return
            helper.showApprovalWaiting(approvalId, sessionId)
        }
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val receipt = DshRepository.apiClient.respondApproval(rpcId, sessionId, approvalId, allow, target)
                android.util.Log.i(TAG, "approval respond: approvalId=$approvalId allow=$allow -> ${receipt::class.simpleName}")
                if (target != null) {
                    when (receipt) {
                        is RespondReceipt.Accepted, is RespondReceipt.NotPending -> {
                            // 其他客户端可能已先处理；旧回执不能重新发布已经失效的通知。
                            if (isCurrentApproval(sessionId, approvalId, target)) {
                                helper.showApprovalWaiting(approvalId, sessionId)
                                if (receipt is RespondReceipt.Accepted) {
                                    DshRepository.notifyInteractionSubmitted(target)
                                }
                            }
                            DshRepository.requestInteractionRefresh(sessionId)
                        }
                        else -> {
                            respondingTargets.remove(approvalId, target)
                            if (isCurrentApproval(sessionId, approvalId, target)) {
                                helper.showApprovalSendFailed(approvalId, sessionId)
                            }
                        }
                    }
                    return@launch
                }
                when (receipt) {
                    is RespondReceipt.Accepted -> helper.cancelApproval(approvalId)
                    is RespondReceipt.NotPending -> {
                        // 已在别处被处理：撤通知（正确行为是提示，不是重试）
                        helper.cancelApproval(approvalId)
                        showToast(appContext, "该审批已在别处处理")
                    }
                    is RespondReceipt.BadResponse,
                    is RespondReceipt.Transport,
                    is RespondReceipt.Malformed,
                    -> helper.showApprovalSendFailed(approvalId, sessionId)
                }
            } catch (e: Exception) {
                // 理论不可达（respond 已全量归一）；兜底改通知文案而非崩溃
                target?.let { respondingTargets.remove(approvalId, it) }
                if (target == null || isCurrentApproval(sessionId, approvalId, target)) {
                    helper.showApprovalSendFailed(approvalId, sessionId)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun isCurrentApproval(sessionId: String, approvalId: String, target: InteractionTarget): Boolean =
        DshRepository.pendingApprovals.value[sessionId]
            ?.any { it.approvalId == approvalId && it.target == target } == true

    private fun showToast(context: Context, text: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "ApprovalReceiver"
        private val respondingTargets = ConcurrentHashMap<String, InteractionTarget>()

        const val EXTRA_RPC_ID = "rpcId"
        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_APPROVAL_ID = "approvalId"
        const val EXTRA_OUTCOME_ALLOW = "outcomeAllow"
        const val EXTRA_TARGET = "interactionTarget"
    }
}
