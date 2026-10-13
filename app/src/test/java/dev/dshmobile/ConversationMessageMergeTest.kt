package dev.dshmobile

import dev.dshmobile.ui.ConversationViewModel
import dev.dshmobile.ui.ConversationViewModel.PromptDeliveryState
import dev.dshmobile.ui.ConversationViewModel.Role
import dev.dshmobile.ui.ConversationViewModel.UiMessage
import dev.dshmobile.ui.HistoryRequestGate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 对话历史轮询收敛本地乐观消息的回归用例。
 *
 * 复现条件：发送先插入 local-user 键的消息，服务端历史使用自己的事件键；
 * 如果只按 key 合并，轮询会把同一条用户消息显示两次。
 */
class ConversationMessageMergeTest {

    @Test
    fun `recent server echo replaces matching local prompt in place`() {
        val local = local("local-user-a", "你好", 100_000L)
        val server = server("seq-10", "你好", 101_000L)

        assertEquals(
            listOf(server),
            ConversationViewModel.mergeHistoryMessages(listOf(local), listOf(server)),
        )
    }

    @Test
    fun `older history with same text does not replace a new local prompt`() {
        val old = server("seq-2", "继续", 500_000L)
        val local = local("local-user-b", "继续", 1_000_000L)

        assertEquals(
            listOf(old, local),
            ConversationViewModel.mergeHistoryMessages(listOf(old, local), listOf(old)),
        )
    }

    @Test
    fun `repeated same text prompts stay ordered when nearest match would cross`() {
        val firstLocal = local("local-user-c", "确认", 100_000L)
        val secondLocal = local("local-user-d", "确认", 106_000L)
        val firstServer = server("seq-20", "确认", 104_000L)
        val secondServer = server("seq-21", "确认", 107_000L)
        val assistantReply = UiMessage(
            key = "seq-20-reply",
            role = Role.ASSISTANT,
            text = "处理中",
            timeMillis = 105_000L,
        )

        assertEquals(
            listOf(firstServer, assistantReply, secondServer),
            ConversationViewModel.mergeHistoryMessages(
                listOf(firstLocal, secondLocal),
                listOf(firstServer, assistantReply, secondServer),
            ),
        )

        // 旧实时入口若先把第一条回显贪心替换到第二个本地槽位，整轮历史仍须纠正顺序。
        assertEquals(
            listOf(firstServer, assistantReply, secondServer),
            ConversationViewModel.mergeHistoryMessages(
                listOf(firstLocal, firstServer),
                listOf(firstServer, assistantReply, secondServer),
            ),
        )
    }

    @Test
    fun `unanchored history precedes messages that arrived after its request`() {
        val local = local("local-user-first", "新问题", 200_000L)
        val streaming = UiMessage(
            key = ConversationViewModel.STREAMING_KEY,
            role = Role.ASSISTANT,
            text = "正在输出",
            timeMillis = 0L,
        )
        val olderHistory = server("seq-15", "旧记录", 100_000L)

        // 首次历史请求开始时列表为空；实时项在响应前到达。历史顺序不依赖两台设备的时钟。
        assertEquals(
            listOf(olderHistory, local, streaming),
            ConversationViewModel.mergeHistoryMessages(
                listOf(local, streaming),
                listOf(olderHistory),
                keysAtRequestStart = emptySet(),
                initialHistoryLoad = true,
            ),
        )

        // 后续请求只把历史插入请求期间新增消息之前，并保留已知前缀与流式尾项。
        val known = server("seq-known", "已知消息", 50_000L)
        val arrivingLocal = local("local-user-second", "另一个问题", 200_000L)
        val arrivingStream = streaming.copy(text = "增量输出")
        val previousHistory = server("seq-14", "中间历史", 100_000L)
        assertEquals(
            listOf(known, previousHistory, arrivingLocal, arrivingStream),
            ConversationViewModel.mergeHistoryMessages(
                listOf(known, arrivingLocal, arrivingStream),
                listOf(previousHistory),
                keysAtRequestStart = setOf(known.key),
            ),
        )

        assertEquals(
            listOf(previousHistory, arrivingStream),
            ConversationViewModel.mergeHistoryMessages(
                current = listOf(arrivingStream),
                incoming = listOf(previousHistory),
                keysAtRequestStart = setOf(ConversationViewModel.STREAMING_KEY),
            ),
        )
    }

    @Test
    fun `missing history stays before a new stream when the next message is an anchor`() {
        val streaming = UiMessage(
            key = ConversationViewModel.STREAMING_KEY,
            role = Role.ASSISTANT,
            text = "正在输出",
            timeMillis = 0L,
        )
        val arrivingMessage = server("seq-arrived", "新到消息", 200_000L)
        val olderHistory = server("seq-older", "旧历史", 100_000L)

        assertEquals(
            listOf(olderHistory, streaming, arrivingMessage),
            ConversationViewModel.mergeHistoryMessages(
                current = listOf(streaming, arrivingMessage),
                incoming = listOf(olderHistory, arrivingMessage),
                keysAtRequestStart = emptySet(),
            ),
        )

        // 普通前缀仍留在历史之前；固定键即使出现在请求起点集合，也表示当前流式占位。
        val known = server("seq-known-before", "已知前缀", 50_000L)
        assertEquals(
            listOf(known, olderHistory, streaming, arrivingMessage),
            ConversationViewModel.mergeHistoryMessages(
                current = listOf(known, streaming, arrivingMessage),
                incoming = listOf(olderHistory, arrivingMessage),
                keysAtRequestStart = setOf(known.key, ConversationViewModel.STREAMING_KEY),
            ),
        )
    }

    @Test
    fun `history request gate skips overlapping fetches and reopens after completion`() {
        val gate = HistoryRequestGate()

        assertEquals(true, gate.tryStart())
        assertEquals(false, gate.tryStart())
        assertEquals(false, gate.tryStart()) // 多次忙时触发只保留一个补查。
        assertEquals(true, gate.finishCurrentRequest())
        assertEquals(false, gate.finishCurrentRequest())
        assertEquals(true, gate.tryStart())
        gate.abort()
    }

    @Test
    fun `missing earlier or distant timestamps do not trigger a text-only match`() {
        val local = local("local-user-e", "检查", 100_000L)
        val missingTime = server("seq-30", "检查", 0L)
        val oneMillisecondEarly = server("seq-31", "检查", 99_999L)
        val distant = server("seq-32", "检查", 220_001L)

        assertEquals(
            listOf(local, missingTime, oneMillisecondEarly, distant),
            ConversationViewModel.mergeHistoryMessages(
                listOf(local),
                listOf(missingTime, oneMillisecondEarly, distant),
            ),
        )
    }

    @Test
    fun `recent earlier history does not replace a newer prompt`() {
        val local = local("local-user-h", "继续检查", 600_000L)
        val earlierServerMessage = server("seq-35", "继续检查", 580_000L)

        assertEquals(
            listOf(local, earlierServerMessage),
            ConversationViewModel.mergeHistoryMessages(listOf(local), listOf(earlierServerMessage)),
        )
    }

    @Test
    fun `business rejected local prompt is kept when same text appears in history`() {
        val rejected = local(
            "local-user-f",
            "不要覆盖我",
            100_000L,
            PromptDeliveryState.REJECTED,
        )
        val server = server("seq-40", "不要覆盖我", 101_000L)

        assertEquals(
            listOf(rejected, server),
            ConversationViewModel.mergeHistoryMessages(listOf(rejected), listOf(server)),
        )
    }

    @Test
    fun `uncertain network result is reconciled when host history confirms it`() {
        val uncertain = local(
            "local-user-g",
            "连接后确认",
            100_000L,
            PromptDeliveryState.UNCONFIRMED,
        )
        val server = server("seq-50", "连接后确认", 102_000L)

        assertEquals(
            listOf(server),
            ConversationViewModel.mergeHistoryMessages(listOf(uncertain), listOf(server)),
        )
    }

    @Test
    fun `older history snapshot does not overwrite a current server message`() {
        val current = server("seq-60", "实时新正文", 101_000L)
        val staleHistory = server("seq-60", "历史旧正文", 100_000L)

        assertEquals(
            listOf(current),
            ConversationViewModel.mergeHistoryMessages(listOf(current), listOf(staleHistory)),
        )
    }

    @Test
    fun `accepted prompt status reflects whether the host was already running`() {
        assertEquals(PromptDeliveryState.SENT, ConversationViewModel.acceptedPromptState(false))
        assertEquals(PromptDeliveryState.QUEUED, ConversationViewModel.acceptedPromptState(true))
        assertEquals(PromptDeliveryState.REJECTED, ConversationViewModel.failedPromptState(networkError = false))
        assertEquals(PromptDeliveryState.UNCONFIRMED, ConversationViewModel.failedPromptState(networkError = true))
    }

    @Test
    fun `latest host running state wins and missing host state restores previous value`() {
        assertEquals(true, ConversationViewModel.restoreRunningAfterFailedPrompt(false, true))
        assertEquals(false, ConversationViewModel.restoreRunningAfterFailedPrompt(true, false))
        assertEquals(true, ConversationViewModel.restoreRunningAfterFailedPrompt(true, null))
        assertEquals(false, ConversationViewModel.restoreRunningAfterFailedPrompt(false, null))
        assertEquals(true, ConversationViewModel.shouldRestoreRunningAfterPromptBatch(true, 0, false))
        assertEquals(false, ConversationViewModel.shouldRestoreRunningAfterPromptBatch(true, 1, false))
        assertEquals(false, ConversationViewModel.shouldRestoreRunningAfterPromptBatch(true, 0, true))
    }

    private fun local(
        key: String,
        text: String,
        time: Long,
        state: PromptDeliveryState = PromptDeliveryState.SENDING,
    ) = UiMessage(
        key = key,
        role = Role.USER,
        text = text,
        timeMillis = time,
        deliveryState = state,
    )

    private fun server(key: String, text: String, time: Long) = UiMessage(
        key = key,
        role = Role.USER,
        text = text,
        timeMillis = time,
    )
}
