package dev.dshmobile

import dev.dshmobile.model.ConversationEvent
import dev.dshmobile.ui.ConversationItem
import dev.dshmobile.ui.ConversationViewModel
import dev.dshmobile.ui.ConversationViewModel.Role
import dev.dshmobile.ui.ConversationViewModel.ToolResultDisplay
import dev.dshmobile.ui.ConversationViewModel.UiMessage
import dev.dshmobile.ui.ThinkingProcessGrouper
import dev.dshmobile.ui.processGroupLabel
import dev.dshmobile.ui.processGroupTailLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 连续思考步骤分组的回归用例。
 *
 * 复现条件：一个回合里模型连续多步只思考并调用工具、不写正文，每一步各占一个气泡，
 * 首行都是「💭 思考过程 · 点按展开」，几屏全是重复的空壳。
 * 规则：连续 2 步及以上的"无正文、无工具结果"步骤合并成一组（默认收起）；
 * 带正文或带工具结果的步骤（用户截图的第一行与最后一行）不并入、不折叠。
 */
class ThinkingProcessGroupingTest {

    // ---------- 合并与打断 ----------

    @Test
    fun `consecutive steps without visible output merge into one group`() {
        val items = ThinkingProcessGrouper.group(
            listOf(
                thinkStep("seq-1", toolCalls = 2),
                thinkStep("seq-2", toolCalls = 1),
                thinkStep("seq-3", toolCalls = 0),
            ),
        )

        val group = assertIs<ConversationItem.ProcessGroup>(items.single())
        assertEquals(listOf("seq-1", "seq-2", "seq-3"), group.steps.map { it.key })
        assertEquals(3, group.toolCallCount)
        assertEquals("process-group:seq-1", group.key)
    }

    @Test
    fun `steps with answer text break the group and stay independent`() {
        val items = ThinkingProcessGrouper.group(
            listOf(
                thinkStep("seq-1"),
                thinkStep("seq-2"),
                answerStep("seq-3", "我先核对本机实际支持情况，再回答。"),
                thinkStep("seq-4"),
                thinkStep("seq-5"),
            ),
        )

        assertEquals(3, items.size)
        assertEquals(listOf("seq-1", "seq-2"), assertIs<ConversationItem.ProcessGroup>(items[0]).steps.map { it.key })
        assertEquals("seq-3", assertIs<ConversationItem.Message>(items[1]).message.key)
        assertEquals(listOf("seq-4", "seq-5"), assertIs<ConversationItem.ProcessGroup>(items[2]).steps.map { it.key })
    }

    @Test
    fun `single step without visible output is not merged`() {
        val items = ThinkingProcessGrouper.group(listOf(thinkStep("seq-1")))

        assertEquals("seq-1", assertIs<ConversationItem.Message>(items.single()).message.key)
    }

    @Test
    fun `steps with tool result output are not folded`() {
        val items = ThinkingProcessGrouper.group(
            listOf(
                thinkStep("seq-1"),
                thinkStep("seq-2"),
                thinkStep("seq-3", toolResults = listOf(ToolResultDisplay("命令输出"))),
                thinkStep("seq-4"),
                thinkStep("seq-5"),
            ),
        )

        assertEquals(3, items.size)
        assertEquals(listOf("seq-1", "seq-2"), assertIs<ConversationItem.ProcessGroup>(items[0]).steps.map { it.key })
        assertEquals("seq-3", assertIs<ConversationItem.Message>(items[1]).message.key)
        assertEquals(listOf("seq-4", "seq-5"), assertIs<ConversationItem.ProcessGroup>(items[2]).steps.map { it.key })
    }

    @Test
    fun `blank tool result entries do not block folding`() {
        // 宿主会给大多数工具步骤挂结果条目，但当前版本这些条目正文是空串；
        // 界面直接跳过空正文结果，用户看不到输出，因此这种步骤仍应折叠。
        val items = ThinkingProcessGrouper.group(
            listOf(
                thinkStep("seq-1", toolResults = listOf(ToolResultDisplay(""), ToolResultDisplay(""))),
                thinkStep("seq-2", toolResults = listOf(ToolResultDisplay(""))),
            ),
        )

        assertEquals(2, assertIs<ConversationItem.ProcessGroup>(items.single()).steps.size)
    }

    @Test
    fun `streaming placeholder never joins a group and breaks the run`() {
        val streaming = UiMessage(
            key = ConversationViewModel.STREAMING_KEY,
            role = Role.ASSISTANT,
            text = "",
            reasoning = "正在思考",
        )
        val items = ThinkingProcessGrouper.group(
            listOf(thinkStep("seq-1"), thinkStep("seq-2"), streaming, thinkStep("seq-3"), thinkStep("seq-4")),
        )

        assertEquals(3, items.size)
        assertEquals(2, assertIs<ConversationItem.ProcessGroup>(items[0]).steps.size)
        assertEquals(ConversationViewModel.STREAMING_KEY, assertIs<ConversationItem.Message>(items[1]).message.key)
        assertEquals(2, assertIs<ConversationItem.ProcessGroup>(items[2]).steps.size)
    }

    @Test
    fun `user and context messages break the run`() {
        val user = UiMessage(key = "user-1", role = Role.USER, text = "dsh会话是否有子agent并行能力?")
        val context = UiMessage(
            key = "inject-1",
            role = Role.CONTEXT,
            text = "全局规则正文",
            contextRole = "inject",
            contextLabel = "~/.dsh/AGENTS.md",
        )
        val items = ThinkingProcessGrouper.group(
            listOf(thinkStep("seq-1"), thinkStep("seq-2"), user, context, thinkStep("seq-3"), thinkStep("seq-4")),
        )

        assertEquals(4, items.size)
        assertEquals(2, assertIs<ConversationItem.ProcessGroup>(items[0]).steps.size)
        assertEquals("user-1", assertIs<ConversationItem.Message>(items[1]).message.key)
        assertEquals("inject-1", assertIs<ConversationItem.Message>(items[2]).message.key)
        assertEquals(2, assertIs<ConversationItem.ProcessGroup>(items[3]).steps.size)
    }

    @Test
    fun `error notice breaks the run`() {
        val error = UiMessage(key = "agent-error-1", role = Role.ERROR, text = "额度已用尽")
        val items = ThinkingProcessGrouper.group(
            listOf(thinkStep("seq-1"), thinkStep("seq-2"), error, thinkStep("seq-3"), thinkStep("seq-4")),
        )

        assertEquals(3, items.size)
        assertEquals("agent-error-1", assertIs<ConversationItem.Message>(items[1]).message.key)
    }

    // ---------- 边界 ----------

    @Test
    fun `blank text counts as no visible output`() {
        val items = ThinkingProcessGrouper.group(
            listOf(
                thinkStep("seq-1", text = "  \n\t "),
                thinkStep("seq-2", text = "\n"),
            ),
        )

        assertEquals(1, items.size)
        assertEquals(2, assertIs<ConversationItem.ProcessGroup>(items.single()).steps.size)
    }

    @Test
    fun `trailing run without a breaker is still grouped`() {
        val user = UiMessage(key = "user-1", role = Role.USER, text = "继续")
        val items = ThinkingProcessGrouper.group(
            listOf(user, thinkStep("seq-1"), thinkStep("seq-2"), thinkStep("seq-3")),
        )

        assertEquals(2, items.size)
        assertEquals(3, assertIs<ConversationItem.ProcessGroup>(items[1]).steps.size)
    }

    @Test
    fun `empty message list produces no items`() {
        assertTrue(ThinkingProcessGrouper.group(emptyList()).isEmpty())
    }

    @Test
    fun `reasoning only steps without tool calls still merge`() {
        val items = ThinkingProcessGrouper.group(
            listOf(thinkStep("seq-1", toolCalls = 0), thinkStep("seq-2", toolCalls = 0)),
        )

        val group = assertIs<ConversationItem.ProcessGroup>(items.single())
        assertEquals(0, group.toolCallCount)
        assertEquals(2, group.steps.size)
    }

    @Test
    fun `caller supplied streaming key is respected`() {
        val custom = UiMessage(key = "custom-stream", role = Role.ASSISTANT, text = "", reasoning = "思考")

        val items = ThinkingProcessGrouper.group(
            listOf(thinkStep("seq-1"), custom),
            streamingKey = "custom-stream",
        )

        assertEquals(2, items.size)
        assertEquals("custom-stream", assertIs<ConversationItem.Message>(items[1]).message.key)
    }

    // ---------- 摘要文案 ----------

    @Test
    fun `group label shows step and tool call counts`() {
        assertEquals(
            "▸ 💭 思考过程 · 3 步 · 5 次工具调用 · 点按展开",
            processGroupLabel(stepCount = 3, toolCallCount = 5, expanded = false),
        )
        assertEquals(
            "▸ 💭 思考过程 · 3 步 · 5 次工具调用 · 点按收起",
            processGroupLabel(stepCount = 3, toolCallCount = 5, expanded = true),
        )
    }

    @Test
    fun `group label omits tool call count when there is none`() {
        assertEquals(
            "▸ 💭 思考过程 · 2 步 · 点按展开",
            processGroupLabel(stepCount = 2, toolCallCount = 0, expanded = false),
        )
    }

    @Test
    fun `tail group keeps fold marker and step count in one line`() {
        // 列表尾部的组收起时这一行后面紧跟"只滚动显示的最新一步内容"，所以只留折叠标识与步数
        assertEquals("▸ 💭 3 步", processGroupTailLabel(3))
        assertEquals("▸ 💭 7 步", processGroupTailLabel(7))
    }

    // ---------- 列表尾部标记 ----------

    @Test
    fun `trailing group is marked as list tail`() {
        val items = ThinkingProcessGrouper.group(listOf(thinkStep("seq-1"), thinkStep("seq-2")))

        assertTrue(assertIs<ConversationItem.ProcessGroup>(items.single()).isTail)
    }

    @Test
    fun `group followed by an answer is not the list tail`() {
        val items = ThinkingProcessGrouper.group(
            listOf(thinkStep("seq-1"), thinkStep("seq-2"), answerStep("seq-3", "带正文的步骤")),
        )

        assertFalse(assertIs<ConversationItem.ProcessGroup>(items[0]).isTail)
    }

    @Test
    fun `group followed by a running placeholder stays the list tail`() {
        // 回合还在跑：组后面跟着流式占位（没有正文），这一组仍是最新一组，收起时要滚动显示最新内容
        val streaming = UiMessage(
            key = ConversationViewModel.STREAMING_KEY,
            role = Role.ASSISTANT,
            text = "",
            reasoning = "正在思考",
        )
        val items = ThinkingProcessGrouper.group(
            listOf(thinkStep("seq-1"), thinkStep("seq-2"), streaming),
        )

        assertTrue(assertIs<ConversationItem.ProcessGroup>(items[0]).isTail)
    }

    @Test
    fun `middle run between two answers is still folded`() {
        // 追加的尾部行为不能影响原有口径：两条带正文回答之间的多步照样折叠
        val items = ThinkingProcessGrouper.group(
            listOf(
                answerStep("seq-1", "第一段"),
                thinkStep("seq-2"), thinkStep("seq-3"), thinkStep("seq-4"),
                answerStep("seq-5", "第二段"),
            ),
        )

        assertEquals(3, items.size)
        val group = assertIs<ConversationItem.ProcessGroup>(items[1])
        assertEquals(3, group.steps.size)
        assertFalse(group.isTail, "中间组不该带尾部标记")
    }

    @Test
    fun `group followed by another group is not the list tail`() {
        val items = ThinkingProcessGrouper.group(
            listOf(
                thinkStep("seq-1"), thinkStep("seq-2"),
                answerStep("seq-3", "中间回答"),
                thinkStep("seq-4"), thinkStep("seq-5"),
            ),
        )

        assertFalse(assertIs<ConversationItem.ProcessGroup>(items[0]).isTail)
        assertTrue(assertIs<ConversationItem.ProcessGroup>(items[2]).isTail)
    }

    // ---------- 组键与状态稳定性 ----------

    @Test
    fun `group key stays stable when the run grows`() {
        // 展开状态按组键记忆：后续步骤并入同一组时首步不变，键必须不变，否则用户展开的组会被弹回收起
        val twoSteps = ThinkingProcessGrouper.group(listOf(thinkStep("seq-1"), thinkStep("seq-2")))
        val threeSteps = ThinkingProcessGrouper.group(
            listOf(thinkStep("seq-1"), thinkStep("seq-2"), thinkStep("seq-3")),
        )

        assertEquals(
            assertIs<ConversationItem.ProcessGroup>(twoSteps.single()).key,
            assertIs<ConversationItem.ProcessGroup>(threeSteps.single()).key,
        )
        assertEquals("process-group:seq-1", assertIs<ConversationItem.ProcessGroup>(threeSteps.single()).key)
    }

    @Test
    fun `two runs in one list get different keys`() {
        val items = ThinkingProcessGrouper.group(
            listOf(
                thinkStep("seq-1"), thinkStep("seq-2"),
                answerStep("seq-3", "中间的回答"),
                thinkStep("seq-4"), thinkStep("seq-5"),
            ),
        )

        val first = assertIs<ConversationItem.ProcessGroup>(items[0]).key
        val second = assertIs<ConversationItem.ProcessGroup>(items[2]).key
        assertTrue(first != second, "两个不同的思考组不能共用组键")
        assertEquals("process-group:seq-1", first)
        assertEquals("process-group:seq-4", second)
    }

    @Test
    fun `grouping does not mutate the input list`() {
        val input = listOf(thinkStep("seq-1"), thinkStep("seq-2"), thinkStep("seq-3"))
        val snapshot = input.toList()

        ThinkingProcessGrouper.group(input)

        assertEquals(snapshot, input)
    }

    // ---------- 打断与边界补充 ----------

    @Test
    fun `single foldable step between answers stays a message`() {
        val items = ThinkingProcessGrouper.group(
            listOf(
                answerStep("seq-1", "第一段"),
                thinkStep("seq-2"),
                answerStep("seq-3", "第二段"),
            ),
        )

        assertEquals(3, items.size)
        assertTrue(items.all { it is ConversationItem.Message }, "夹在两条回答之间的单步不构成连续，不合并")
        assertEquals(
            listOf("seq-1", "seq-2", "seq-3"),
            items.map { assertIs<ConversationItem.Message>(it).message.key },
        )
    }

    @Test
    fun `standalone tool message breaks the run`() {
        val toolOutput = UiMessage(key = "tool-1", role = Role.TOOL, text = "命令输出")
        val items = ThinkingProcessGrouper.group(
            listOf(thinkStep("seq-1"), thinkStep("seq-2"), toolOutput, thinkStep("seq-3"), thinkStep("seq-4")),
        )

        assertEquals(3, items.size)
        assertEquals("tool-1", assertIs<ConversationItem.Message>(items[1]).message.key)
    }

    @Test
    fun `blank standalone tool message also breaks the run`() {
        // 空正文的独立工具结果在界面上不显示（CollapsibleToolResult 对空正文直接跳过），
        // 但方案第 9 节明确它打断连续批次；这条用例把现状钉住，将来若放宽口径会先红
        val blankTool = UiMessage(key = "tool-blank", role = Role.TOOL, text = "")
        val items = ThinkingProcessGrouper.group(
            listOf(thinkStep("seq-1"), thinkStep("seq-2"), blankTool, thinkStep("seq-3"), thinkStep("seq-4")),
        )

        assertEquals(3, items.size)
        assertEquals("tool-blank", assertIs<ConversationItem.Message>(items[1]).message.key)
    }

    @Test
    fun `whitespace-only tool result does not block folding`() {
        val items = ThinkingProcessGrouper.group(
            listOf(
                thinkStep("seq-1", toolResults = listOf(ToolResultDisplay(" \n\t "))),
                thinkStep("seq-2", toolResults = listOf(ToolResultDisplay("\n\n"))),
            ),
        )

        assertEquals(2, assertIs<ConversationItem.ProcessGroup>(items.single()).steps.size)
    }

    @Test
    fun `only answers produce no group`() {
        val items = ThinkingProcessGrouper.group(
            listOf(answerStep("seq-1", "第一段"), answerStep("seq-2", "第二段")),
        )

        assertEquals(2, items.size)
        assertTrue(items.all { it is ConversationItem.Message })
    }

    @Test
    fun `group label for expanded run without tool calls`() {
        assertEquals(
            "▸ 💭 思考过程 · 2 步 · 点按收起",
            processGroupLabel(stepCount = 2, toolCallCount = 0, expanded = true),
        )
    }

    // ---------- 夹具 ----------

    /** 只有思考与工具调用、没有正文的步骤（截图中间那些气泡）。 */
    private fun thinkStep(
        key: String,
        text: String = "",
        toolCalls: Int = 1,
        toolResults: List<ToolResultDisplay> = emptyList(),
    ): UiMessage = UiMessage(
        key = key,
        role = Role.ASSISTANT,
        text = text,
        reasoning = "我在查本机安装情况",
        toolCalls = (1..toolCalls).map { index ->
            ConversationEvent.ToolCall(
                id = "$key-call-$index",
                name = "pwsh",
                arguments = "{\"command\":\"Get-ChildItem -Force ~/.dsh\"}",
            )
        },
        toolResults = toolResults,
    )

    /** 带正文的步骤（截图第一行与最后一行那种）。 */
    private fun answerStep(key: String, text: String): UiMessage = UiMessage(
        key = key,
        role = Role.ASSISTANT,
        text = text,
        reasoning = "我在组织回答",
    )
}
