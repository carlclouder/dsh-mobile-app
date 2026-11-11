package dev.dshmobile

import dev.dshmobile.model.HostFrame
import dev.dshmobile.model.SessionSummary
import dev.dshmobile.service.NotificationStateMachine
import dev.dshmobile.ui.SessionListViewModel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 会话列表行为回归（2026-11-12 用户反馈第 3、5 条）：
 * - 第 3 条"新建会话返回列表后数秒消失"：宿主会裁掉没有消息的 blank 会话，
 *   本机新建的会话在 baseline 快照与 remove 帧里都必须保留。
 * - 第 5 条"等你输入"分组塞满早已过时的会话：分组只认宿主挂起的审批/提问，
 *   不能再按"7 天内有更新"判定。
 */
class SessionListBehaviorTest {

    @AfterTest
    fun clearRepositoryState() {
        DshRepository.setPendingApprovals(emptyMap())
        DshRepository.setPendingQuestions(emptyMap())
    }

    @Test
    fun `本机新建会话在宿主裁掉后仍保留在快照里`() {
        val machine = NotificationStateMachine()
        // 先按一次宿主快照建立该会话的状态（真实时序：创建后第一轮 baseline 能读到它）
        machine.onBaseline(listOf(summary("new-1", running = false)))
        assertTrue(machine.sessionSnapshot().containsKey("new-1"))

        // 宿主随后裁掉 blank 会话：列表里不再有 new-1
        machine.setLocallyCreatedSessionIds(setOf("new-1"))
        machine.onBaseline(listOf(summary("other", running = false)))

        assertTrue(
            machine.sessionSnapshot().containsKey("new-1"),
            "本机新建的会话被 baseline 对账删掉了，列表行会再次消失",
        )
    }

    @Test
    fun `已转正的本机新建会话不再豁免宿主裁撤`() {
        val machine = NotificationStateMachine()
        machine.onBaseline(listOf(summary("new-1", running = true, blank = false)))
        machine.setLocallyCreatedSessionIds(setOf("new-1"))

        // running=true 会清 blank（宿主已把它转正）：此后宿主列表里没有它，就应按权威列表删除
        machine.onBaseline(listOf(summary("other", running = false)))

        assertFalse(
            machine.sessionSnapshot().containsKey("new-1"),
            "已转正会话不应靠本地创建记录无限保留（否则用户删掉后会留幽灵行）",
        )
    }

    @Test
    fun `宿主不存在的新建例外只对本机记录生效`() {
        val machine = NotificationStateMachine()
        machine.onBaseline(listOf(summary("new-1", running = false), summary("old-1", running = false)))

        // 没有本机创建记录时，宿主裁掉的会话仍按原逻辑清除（防幽灵）
        machine.onBaseline(listOf(summary("other", running = false)))

        assertFalse(machine.sessionSnapshot().containsKey("old-1"))
        assertFalse(machine.sessionSnapshot().containsKey("new-1"))
    }

    @Test
    fun `移除帧不会删掉本机新建会话但会删掉普通会话`() {
        val machine = NotificationStateMachine()
        machine.onBaseline(listOf(summary("new-1", running = false), summary("old-1", running = false)))
        machine.setLocallyCreatedSessionIds(setOf("new-1"))

        machine.onHostFrame(HostFrame.SessionRemoved("new-1"))
        machine.onHostFrame(HostFrame.SessionRemoved("old-1"))

        val snapshot = machine.sessionSnapshot()
        assertTrue(snapshot.containsKey("new-1"))
        assertFalse(snapshot.containsKey("old-1"))
    }

    @Test
    fun `等你输入只看挂起审批或提问`() {
        val viewModel = SessionListViewModel()
        val now = 1_700_000_000_000L
        // s1 一分钟前有更新（旧实现会判成"等你输入"），s2 没有待办，s3 运行中
        val snapshot = mapOf(
            "s1" to state("s1", updatedAt = (now - 60_000).toDouble()),
            "s2" to state("s2", updatedAt = (now - 30_000).toDouble()),
            "s3" to state("s3", updatedAt = (now - 10_000).toDouble(), running = true),
        )

        val idleRows = viewModel.rowsFrom(snapshot, now, emptyList())
        assertTrue(
            idleRows.none { it.group == SessionListViewModel.Group.WAITING },
            "没有任何挂起审批/提问时不应出现等你输入分组",
        )
        assertEquals(SessionListViewModel.Group.RUNNING, idleRows.first { it.sessionId == "s3" }.group)

        DshRepository.setPendingApprovals(mapOf("s2" to listOf(approval("s2"))))
        val waitingRows = viewModel.rowsFrom(snapshot, now, emptyList())

        assertEquals(SessionListViewModel.Group.WAITING, waitingRows.first { it.sessionId == "s2" }.group)
        assertEquals(SessionListViewModel.Group.IDLE, waitingRows.first { it.sessionId == "s1" }.group)
    }

    @Test
    fun `挂起提问同样算等你输入`() {
        val viewModel = SessionListViewModel()
        val now = 1_700_000_000_000L
        val snapshot = mapOf("s1" to state("s1", updatedAt = (now - 1_000).toDouble()))

        DshRepository.setPendingQuestions(
            mapOf("s1" to listOf(question("s1"))),
        )

        val rows = viewModel.rowsFrom(snapshot, now, emptyList())
        assertEquals(SessionListViewModel.Group.WAITING, rows.single().group)
    }

    private fun state(
        id: String,
        updatedAt: Double,
        running: Boolean = false,
    ): NotificationStateMachine.SessionState = NotificationStateMachine.SessionState(
        running = running,
        title = id,
        cwd = "D:\\proj",
        updatedAt = updatedAt,
        blank = false,
    )

    private fun summary(id: String, running: Boolean, blank: Boolean = true): SessionSummary = SessionSummary(
        sessionId = id,
        updatedAt = 1.0,
        running = running,
        blank = blank,
        origin = null,
        cwd = "D:\\proj",
    )

    private fun approval(sessionId: String) = NotificationStateMachine.ApprovalInfo(
        rpcId = "rpc-$sessionId",
        sessionId = sessionId,
        approvalId = "approval-$sessionId",
        toolName = "Bash",
        reason = null,
    )

    private fun question(sessionId: String) = NotificationStateMachine.QuestionInfo(
        rpcId = "rpc-q-$sessionId",
        sessionId = sessionId,
        firstQuestionText = "要继续吗？",
    )
}
