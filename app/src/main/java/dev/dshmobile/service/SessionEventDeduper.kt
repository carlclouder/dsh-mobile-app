package dev.dshmobile.service

/**
 * 会话事件按 seq 去重（修复"流式输出叠词"：一个词重复两次、整句每词重复）。
 *
 * 背景（2026-08-29 实证）：宿主事件日志 seq 严格递增且全唯一（本机 24892 事件核验），
 * 叠词来自传输/重连层的偶发重复投递（手机 Tailscale 慢链路高发），而 App 流式聚合
 * （chunk 文本追加 + 工具结果 append）不是幂等的——同一帧收两次就重复一次。
 * 宿主语义参考：连接建立时推送 session/subscribed.lastSeq 作为去重基线（WebUI 同用途）。
 *
 * 纯 Kotlin、无 Android 依赖 → JVM 可单测；线程安全（服务层多套接字回调并发调用）。
 */
class SessionEventDeduper {

    /** 每会话已放行的最大 seq（水位）；只放行严格更大的 seq。 */
    private val lastSeqPerSession = HashMap<String, Long>()

    /**
     * 判定一帧是否放行：
     * - seq<=0（帧无序号/畸形）：放行但不推进水位（不阻塞后续正常帧）；
     * - seq > 水位：首见或更新 → 放行并推进水位；
     * - seq <= 水位：重复投递/迟到帧/乱序回退 → 丢弃。
     */
    @Synchronized fun admit(sessionId: String, seq: Long): Boolean {
        if (seq <= 0) return true
        val last = lastSeqPerSession[sessionId] ?: 0L
        if (seq <= last) return false
        lastSeqPerSession[sessionId] = seq
        return true
    }
}
