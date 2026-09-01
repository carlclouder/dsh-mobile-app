package dev.dshmobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * Application 入口：通知通道初始化。
 * 通道设计见 docs/DESIGN.md §5.3（四通道：会话动态/审批/提问/后台监听）。
 */
class DshApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // 单例状态源初始化（服务/接收器/UI 共用的 apiClient 与 settingsStore）
        DshRepository.initialize(this)
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 更新后强制重建提醒通道：Android 对已存在通道保留旧设置（install -r 升级时新配置不生效），
        // 先删后建可确保声音+震动配置落地。
        listOf(CHANNEL_EVENTS, CHANNEL_APPROVAL, CHANNEL_QUESTION, CHANNEL_SERVICE).forEach {
            try { manager.deleteNotificationChannel(it) } catch (e: Exception) { }
        }
        // 提醒类通道：统一显式配置声音+震动（避免系统默认关闭），保证真机"叮+震"
        val attrs = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
            .build()
        val vibrate = longArrayOf(0, 250, 120, 100)   // 首次 250ms，停 120ms，再 100ms

        val events = NotificationChannel(
            CHANNEL_EVENTS, "会话动态", NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "回合完成，等待你的输入"
            enableVibration(true)
            this.vibrationPattern = vibrate
            setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, attrs)
        }

        val approval = NotificationChannel(
            CHANNEL_APPROVAL, "需要你审批", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "会话请求执行敏感操作时的审批提醒"
            enableVibration(true)
            this.vibrationPattern = vibrate
            setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, attrs)
        }

        val question = NotificationChannel(
            CHANNEL_QUESTION, "Agent 提问", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Agent 向你提问时的高优先级提醒"
            enableVibration(true)
            this.vibrationPattern = vibrate
            setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, attrs)
        }

        val service = NotificationChannel(
            CHANNEL_SERVICE, "后台监听", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "DSH 连接监听服务常驻通知"
            setShowBadge(false)
        }

        manager.createNotificationChannels(listOf(events, approval, question, service))
    }

    companion object {
        const val CHANNEL_EVENTS = "ch_events"
        const val CHANNEL_APPROVAL = "ch_approval"
        const val CHANNEL_QUESTION = "ch_question"
        const val CHANNEL_SERVICE = "ch_service"
    }
}
