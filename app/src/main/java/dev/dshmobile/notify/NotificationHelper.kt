package dev.dshmobile.notify

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.dshmobile.DshApplication
import dev.dshmobile.R
import dev.dshmobile.data.NotificationToggles
import dev.dshmobile.service.NotificationStateMachine
import dev.dshmobile.ui.ConversationActivity

/**
 * 通知执行层（设计 §5.3）：把状态机的 Command 翻译成系统通知调用。
 *
 * 通知 ID 约定（键控去重的第二道保险，§5.3 P2）：
 * - 回合完成：sessionId.hashCode()             —— 同会话覆盖不堆叠
 * - 审批：("approval:"+approvalId).hashCode()   —— replay 同 id 覆盖实现静默更新
 * - 提问：("question:"+rpcId).hashCode()
 *
 * 审批按钮 PendingIntent extras 自包含全部应答字段（进程被杀后 Receiver 不依赖内存表，§5.3 P2）。
 * 所有发布调用前检查 POST_NOTIFICATIONS 权限（Android 13+ 未授权时静默跳过）。
 */
class NotificationHelper(private val context: Context) {

    companion object {
        /** 常驻前台服务通知 id（EventStreamService 使用）。 */
        const val SERVICE_NOTIFICATION_ID = 1

        private const val REQUEST_CODE_ALLOW = 1
        private const val REQUEST_CODE_REJECT = 2

        fun turnCompleteId(sessionId: String): Int = sessionId.hashCode()
        fun approvalNotificationId(approvalId: String): Int = "approval:$approvalId".hashCode()
        fun questionNotificationId(rpcId: String): Int = "question:$rpcId".hashCode()
    }

    /** 通知开关快照（不可变数据类引用，写于设置收集协程、读于 OkHttp 回调线程——@Volatile 保可见性，终审 P2-4）。 */
    @Volatile
    private var toggles: NotificationToggles = NotificationToggles()

    fun applyToggles(t: NotificationToggles) {
        toggles = t
    }

    // -------------------------------------------------------------------
    // 发布 / 撤销
    // -------------------------------------------------------------------

    /** 回合完成通知（通知①）。同会话同 id 覆盖不堆叠；点击进入对话页。 */
    fun notifyTurnComplete(sessionId: String, displayTitle: String) {
        if (!toggles.turnComplete || !hasNotificationPermission()) return
        val notification = baseBuilder(DshApplication.CHANNEL_EVENTS)
            .setContentTitle(displayTitle)
            .setContentText("回合完成，等待你的输入")
            .setContentIntent(conversationPendingIntent(sessionId))
            .setAutoCancel(true)
            .build()
        notifyInternal(turnCompleteId(sessionId), notification)
    }

    /** 审批通知（通知②，用户拍板要做按钮）：允许一次 / 拒绝，extras 自包含应答字段。 */
    fun notifyApproval(info: NotificationStateMachine.ApprovalInfo) {
        if (!toggles.approval || !hasNotificationPermission()) return
        val text = info.reason ?: "会话需要执行敏感操作"
        val notification = baseBuilder(DshApplication.CHANNEL_APPROVAL)
            .setContentTitle("审批请求：${info.toolName}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(conversationPendingIntent(info.sessionId))
            .addAction(0, "允许一次", approvalPendingIntent(info, allowed = true))
            .addAction(0, "拒绝", approvalPendingIntent(info, allowed = false))
            .setAutoCancel(false) // 由应答结果驱动撤除（Receiver 调 cancel）
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE) // 锁屏不露按钮细节
            .setOnlyAlertOnce(true) // replay 同 id 覆盖时不重响（设计 §3.2 P2 去重）
            .build()
        notifyInternal(approvalNotificationId(info.approvalId), notification)
    }

    /** 提问通知（通知③）：点击进对话页（GUI 内呈现答题卡，v1 不在通知上直接答）。 */
    fun notifyQuestion(info: NotificationStateMachine.QuestionInfo) {
        if (!toggles.question || !hasNotificationPermission()) return
        val notification = baseBuilder(DshApplication.CHANNEL_QUESTION)
            .setContentTitle("Agent 提问")
            .setContentText(info.firstQuestionText.take(30))
            .setContentIntent(conversationPendingIntent(info.sessionId))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true) // replay 同 id 覆盖时不重响
            .build()
        notifyInternal(questionNotificationId(info.rpcId), notification)
    }

    /** 审批通知文案更新为"应答失败"（BadResponse/Transport 分支，§5.3）。 */
    fun showApprovalSendFailed(approvalId: String) {
        if (!hasNotificationPermission()) return
        val notification = baseBuilder(DshApplication.CHANNEL_APPROVAL)
            .setContentTitle("应答失败")
            .setContentText("请打开 APP 重试")
            .setAutoCancel(true)
            .build()
        notifyInternal(approvalNotificationId(approvalId), notification)
    }

    fun cancelTurnComplete(sessionId: String) =
        NotificationManagerCompat.from(context).cancel(turnCompleteId(sessionId))

    fun cancelApproval(approvalId: String) =
        NotificationManagerCompat.from(context).cancel(approvalNotificationId(approvalId))

    fun cancelQuestion(rpcId: String) =
        NotificationManagerCompat.from(context).cancel(questionNotificationId(rpcId))

    // -------------------------------------------------------------------
    // 内部
    // -------------------------------------------------------------------

    private fun baseBuilder(channelId: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)

    private fun notifyInternal(id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (security: SecurityException) {
            // POST_NOTIFICATIONS 未授权（hasNotificationPermission 之外的 ROM 差异兜底）：静默跳过
        }
    }

    private fun hasNotificationPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** 对话页跳转：会话 id 经 extras 传递。 */
    private fun conversationPendingIntent(sessionId: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            Intent(context, ConversationActivity::class.java)
                .putExtra(ConversationActivity.EXTRA_SESSION_ID, sessionId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** 审批按钮：extras 自包含 {rpcId, sessionId, approvalId, allow}（进程被杀也能应答）。 */
    private fun approvalPendingIntent(
        info: NotificationStateMachine.ApprovalInfo,
        allowed: Boolean,
    ): PendingIntent {
        val intent = Intent(context, ApprovalReceiver::class.java)
            .putExtra(ApprovalReceiver.EXTRA_RPC_ID, info.rpcId)
            .putExtra(ApprovalReceiver.EXTRA_SESSION_ID, info.sessionId)
            .putExtra(ApprovalReceiver.EXTRA_APPROVAL_ID, info.approvalId)
            .putExtra(ApprovalReceiver.EXTRA_OUTCOME_ALLOW, allowed)
            // action 携带 approvalId+方向：同一审批的两个按钮互不覆盖 PendingIntent
            .setAction("${info.approvalId}:${if (allowed) "allow" else "reject"}")
        return PendingIntent.getBroadcast(
            context,
            if (allowed) REQUEST_CODE_ALLOW else REQUEST_CODE_REJECT,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
