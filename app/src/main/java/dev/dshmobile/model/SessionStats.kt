package dev.dshmobile.model

import kotlinx.serialization.Serializable

/**
 * 会话统计（需求：底部信息栏——轮/步、LLM/工具耗时、首token、tok/s）。
 * 对应 session/projection key="sessionStats" 的 value（dsh-session-stats）。
 * 缓存命中%与输入量额外由 usage 累计推导。
 */
@Serializable
data class SessionStats(
    val turns: Int = 0,
    val steps: Int = 0,
    val llmMs: Long = 0,
    val toolMs: Long = 0,
    val ttftMs: Long = 0,
    val ttftSteps: Int = 0,
    val decodeMs: Long = 0,
    val decodeTokens: Long = 0,
)
