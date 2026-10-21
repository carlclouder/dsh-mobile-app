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

    @Test
    fun `同一请求编号在时钟偏差缺时间和长排队后仍排在回复前`() {
        val prefix = UiMessage("prefix", Role.ASSISTANT, "上一轮回复")
        val local = local("local-user-clock", "检查状态", 100_000L).copy(promptRequestId = "request-clock")
        val reply = UiMessage("reply-clock", Role.ASSISTANT, "已检查")
        for (serverTime in listOf(0L, 99_999L, 40_000L, 400_001L)) {
            val confirmed = server("server-clock", "检查状态", serverTime).copy(promptRequestId = "request-clock")
            assertEquals(
                listOf(prefix, confirmed, reply),
                ConversationViewModel.mergeHistoryMessages(listOf(prefix, local), listOf(prefix, confirmed, reply)),
                "服务端时间 $serverTime 不应使本地消息留在回复之后",
            )
        }
    }

    @Test
    fun `同一请求编号即使正文变化也只保留正式用户消息`() {
        val local = local("local-user-text", "检查\n当前 状态", 100_000L).copy(promptRequestId = "request-text")
        val confirmed = server("server-text", "检查\n当前状态", 0L).copy(promptRequestId = "request-text")
        val reply = UiMessage("reply-text", Role.ASSISTANT, "已检查")
        assertEquals(
            listOf(confirmed, reply),
            ConversationViewModel.mergeHistoryMessages(listOf(local), listOf(confirmed, reply)),
        )
    }

    @Test
    fun `重复同文发送按各自编号与回复保持服务端顺序`() {
        val first = local("local-user-first-id", "继续", 200_000L).copy(promptRequestId = "request-first")
        val second = local("local-user-second-id", "继续", 201_000L).copy(promptRequestId = "request-second")
        val firstServer = server("server-first-id", "继续", 100_000L).copy(promptRequestId = "request-first")
        val secondServer = server("server-second-id", "继续", 101_000L).copy(promptRequestId = "request-second")
        val firstReply = UiMessage("reply-first-id", Role.ASSISTANT, "第一轮")
        val secondReply = UiMessage("reply-second-id", Role.ASSISTANT, "第二轮")
        val history = listOf(firstServer, firstReply, secondServer, secondReply)
        assertEquals(history, ConversationViewModel.mergeHistoryMessages(listOf(first, second), history))
    }

    @Test
    fun `两个不同的非空请求编号不能因相同文字和时间而误配`() {
        val local = local("local-user-different-id", "继续", 100_000L).copy(promptRequestId = "request-new")
        val another = server("server-different-id", "继续", 100_001L).copy(promptRequestId = "request-other")
        assertEquals(
            listOf(local, another),
            ConversationViewModel.mergeHistoryMessages(listOf(local), listOf(another)),
        )
        assertEquals(listOf(local, another), ConversationViewModel.mergeMessageUpdate(listOf(local), another))
    }

    @Test
    fun `历史补齐同键请求元数据并去掉已确认的尾部本地副本`() {
        val cached = server("server-cached", "已有正式正文", 10L)
        val incoming = cached.copy(text = "较旧正文", promptRequestId = "request-cached")
        val local = local("local-user-cached", "本地正文", 200_000L).copy(promptRequestId = "request-cached")
        val reply = UiMessage("reply-cached", Role.ASSISTANT, "最终回复")
        assertEquals(
            listOf(cached.copy(promptRequestId = "request-cached"), reply),
            ConversationViewModel.mergeHistoryMessages(listOf(cached, reply, local), listOf(incoming, reply)),
        )
    }

    @Test
    fun `实时同键更新保留正式消息位置并移除同编号本地副本`() {
        val confirmed = server("server-live", "服务器正文", 0L).copy(promptRequestId = "request-live")
        val local = local("local-user-live", "本地正文", 200_000L).copy(promptRequestId = "request-live")
        val reply = UiMessage("reply-live", Role.ASSISTANT, "最终回复")
        assertEquals(
            listOf(confirmed, reply),
            ConversationViewModel.mergeMessageUpdate(listOf(confirmed, reply, local), confirmed),
        )
    }

    @Test
    fun `实时回显优先按编号而非时间和正文替换本地槽位`() {
        val local = local("local-user-live-new", "本地正文", 200_000L).copy(promptRequestId = "request-live-new")
        val confirmed = server("server-live-new", "正式正文", 1L).copy(promptRequestId = "request-live-new")
        assertEquals(listOf(confirmed), ConversationViewModel.mergeMessageUpdate(listOf(local), confirmed))
    }

    @Test
    fun `已按编号确认的消息不再参加旧宿主同文匹配`() {
        val identified = local("local-user-identified", "确认", 100_000L).copy(promptRequestId = "request-known")
        val legacy = local("local-user-legacy", "确认", 100_005L)
        val identifiedServer = server("server-identified", "确认", 0L).copy(promptRequestId = "request-known")
        val legacyServer = server("server-legacy", "确认", 100_010L)
        val reply = UiMessage("reply-mixed", Role.ASSISTANT, "已确认")
        val history = listOf(identifiedServer, reply, legacyServer)
        assertEquals(history, ConversationViewModel.mergeHistoryMessages(listOf(identified, legacy), history))
    }

    @Test
    fun `网络受理不确定可由相同编号历史确认`() {
        val local = local("local-user-uncertain-id", "检查", 200_000L, PromptDeliveryState.UNCONFIRMED)
            .copy(promptRequestId = "request-uncertain")
        val confirmed = server("server-uncertain-id", "检查", 0L).copy(promptRequestId = "request-uncertain")
        assertEquals(listOf(confirmed), ConversationViewModel.mergeHistoryMessages(listOf(local), listOf(confirmed)))
    }

    @Test
    fun `已经错序的副本经历史纠正后再次对账仍保持同序且不重复`() {
        val local = local("local-user-repeat-id", "状态", 200_000L).copy(promptRequestId = "request-repeat")
        val confirmed = server("server-repeat-id", "状态", 1L).copy(promptRequestId = "request-repeat")
        val reply = UiMessage("reply-repeat-id", Role.ASSISTANT, "当前状态")
        val expected = listOf(confirmed, reply)
        val corrected = ConversationViewModel.mergeHistoryMessages(listOf(confirmed, reply, local), expected)
        assertEquals(expected, corrected)
        assertEquals(expected, ConversationViewModel.mergeHistoryMessages(corrected, expected))
    }

    @Test
    fun `实时缺编号更新先补已知身份再匹配且不能误删另一条同文发送`() {
        val confirmed = server("server-known", "继续", 100_001L).copy(promptRequestId = "request-known")
        val other = local("local-user-other", "继续", 100_000L).copy(promptRequestId = "request-other")
        val update = confirmed.copy(promptRequestId = null)
        assertEquals(
            listOf(confirmed, other),
            ConversationViewModel.mergeMessageUpdate(listOf(confirmed, other), update),
        )
    }

    @Test
    fun `实时缺编号更新用已有身份清理时钟偏差的同编号本地副本`() {
        val confirmed = server("server-known-clock", "服务器正文", 1L).copy(promptRequestId = "request-known-clock")
        val local = local("local-user-known-clock", "本地正文", 200_000L).copy(promptRequestId = "request-known-clock")
        val reply = UiMessage("reply-known-clock", Role.ASSISTANT, "最终回复")
        assertEquals(
            listOf(confirmed, reply),
            ConversationViewModel.mergeMessageUpdate(listOf(confirmed, reply, local), confirmed.copy(promptRequestId = null)),
        )
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
