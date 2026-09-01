package dev.dshmobile.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import dev.dshmobile.DshRepository
import dev.dshmobile.model.RespondReceipt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 审批通知按钮接收器（设计 §5.3）：
 * - extras 自包含应答字段（rpcId/sessionId/approvalId/outcome），进程被杀后不依赖内存表
 * - goAsync + 协程：应答是网络调用（10s 级），不能阻塞主线程广播队列（10s ANR 阈值）
 * - 回执三态语义（§3.1）：Accepted→撤通知；NotPending→撤通知+提示"已在别处处理"（不重试）；
 *   BadResponse/Transport/Malformed→通知改"应答失败，请打开 APP 重试"
 */
class ApprovalReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val rpcId = intent.getStringExtra(EXTRA_RPC_ID) ?: return
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val approvalId = intent.getStringExtra(EXTRA_APPROVAL_ID) ?: return
        val allow = intent.getBooleanExtra(EXTRA_OUTCOME_ALLOW, false)

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val receipt = DshRepository.apiClient.respondApproval(rpcId, sessionId, approvalId, allow)
                android.util.Log.i(TAG, "approval respond: approvalId=$approvalId allow=$allow -> ${receipt::class.simpleName}")
                val helper = NotificationHelper(appContext)
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
                    -> helper.showApprovalSendFailed(approvalId)
                }
            } catch (e: Exception) {
                // 理论不可达（respond 已全量归一）；兜底改通知文案而非崩溃
                NotificationHelper(appContext).showApprovalSendFailed(approvalId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showToast(context: Context, text: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "ApprovalReceiver"

        const val EXTRA_RPC_ID = "rpcId"
        const val EXTRA_SESSION_ID = "sessionId"
        const val EXTRA_APPROVAL_ID = "approvalId"
        const val EXTRA_OUTCOME_ALLOW = "outcomeAllow"
    }
}
