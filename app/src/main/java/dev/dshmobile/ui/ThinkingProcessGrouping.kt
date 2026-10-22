package dev.dshmobile.ui

import dev.dshmobile.ui.ConversationViewModel.Role
import dev.dshmobile.ui.ConversationViewModel.UiMessage

/**
 * 会话列表渲染单元：一条独立消息，或一组连续"只有思考与工具调用、没有具体输出"的步骤。
 *
 * 为什么在渲染层分组而不改消息模型：消息列表同时被历史对账、顺序对齐、滚动定位使用，
 * 上一轮刚修完顺序问题。分组只是显示口径，不能反过来影响消息的产生与合并。
 */
internal sealed interface ConversationItem {

    /** 一条按原样渲染的消息。 */
    data class Message(val message: UiMessage) : ConversationItem

    /**
     * 连续的思考步骤组（默认收起，点按展开）。
     * @param key 组键 = 组内首步消息键加前缀；首步被历史对账替换时组自然重组，展开状态不会串到别的组
     * @param steps 组内各步，保持消息原顺序
     * @param toolCallCount 组内各步工具调用数之和，仅用于摘要文案
     */
    data class ProcessGroup(
        val key: String,
        val steps: List<UiMessage>,
        val toolCallCount: Int,
    ) : ConversationItem
}

/**
 * 连续思考步骤分组器（纯 Kotlin，可在 JVM 单测里直接调用）。
 *
 * 口径（对应用户确认的方案）：
 * - 可折叠步骤 = 助手消息 + 不是流式占位 + 正文空白 + 没有任何非空工具结果正文；
 *   在界面上只剩思考标签和工具调用一行摘要，属于"没有具体输出的思考框"。
 * - 连续 [MIN_STEPS] 步及以上才合并成一组；只有一步时不合并，因为它不构成"连续"。
 * - 带正文、带可见工具结果的步骤一律不并入、不折叠（用户截图第一行与最后一行的情形）。
 * - 用户消息、上下文注入、错误提示、独立工具结果、流式占位都会打断连续批次。
 */
internal object ThinkingProcessGrouper {

    /** 合并成组所需的最少步数。 */
    const val MIN_STEPS = 2

    /** 组键前缀：与消息键区分，避免展开状态和消息键互相污染。 */
    private const val GROUP_KEY_PREFIX = "process-group:"

    /**
     * 把消息列表投影为渲染项列表。
     * @param messages 会话消息（ViewModel 输出的原顺序）
     * @param streamingKey 流式占位消息键（该条正文与思考还在增长，永不入组）
     */
    fun group(
        messages: List<UiMessage>,
        streamingKey: String = ConversationViewModel.STREAMING_KEY,
    ): List<ConversationItem> {
        val items = ArrayList<ConversationItem>(messages.size)
        val batch = ArrayList<UiMessage>()

        // 结算当前累加批次：≥2 步成组，1 步原样输出，0 步什么也不做
        fun flushBatch() {
            when {
                batch.isEmpty() -> Unit
                batch.size >= MIN_STEPS -> items.add(
                    ConversationItem.ProcessGroup(
                        key = GROUP_KEY_PREFIX + batch.first().key,
                        steps = batch.toList(),
                        toolCallCount = batch.sumOf { it.toolCalls.size },
                    ),
                )
                else -> items.add(ConversationItem.Message(batch.first()))
            }
            batch.clear()
        }

        for (message in messages) {
            if (isFoldableStep(message, streamingKey)) {
                batch.add(message)
            } else {
                flushBatch()
                items.add(ConversationItem.Message(message))
            }
        }
        flushBatch()   // 末尾未被打断的批次同样要结算
        return items
    }

    /**
     * 该步是否属于"没有具体输出、可以收进思考组"的步骤。
     *
     * "有没有输出"按界面实际能不能看到判定，不按数据结构里有没有条目判定：
     * 宿主会给多数工具步骤挂 tool/result 条目，但当前版本这些条目的正文是空串
     * （历史实测 188 个无正文步骤中 187 个挂着结果条目、正文长度全为 0），
     * 界面按 [ConversationActivity] 的规则直接跳过空正文结果，用户看不到任何输出。
     * 所以只要正文空白且没有任何非空结果正文，就是"只有思考标签和工具调用行"的步骤。
     */
    fun isFoldableStep(message: UiMessage, streamingKey: String = ConversationViewModel.STREAMING_KEY): Boolean =
        message.role == Role.ASSISTANT &&
            message.key != streamingKey &&
            message.text.isBlank() &&
            message.toolResults.none { it.text.isNotBlank() }
}

/**
 * 组摘要单行文案：收起态提示"点按展开"，展开态提示"点按收起"。
 * 没有工具调用时（只连续思考）不显示"0 次工具调用"，避免噪声。
 */
internal fun processGroupLabel(stepCount: Int, toolCallCount: Int, expanded: Boolean): String {
    val action = if (expanded) "点按收起" else "点按展开"
    return if (toolCallCount > 0) {
        "💭 思考过程 · $stepCount 步 · $toolCallCount 次工具调用 · $action"
    } else {
        "💭 思考过程 · $stepCount 步 · $action"
    }
}
