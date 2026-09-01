package dev.dshmobile

import dev.dshmobile.model.HostFrame
import dev.dshmobile.model.MuxFrame
import dev.dshmobile.model.QuestionItem
import dev.dshmobile.model.SessionSummary
import dev.dshmobile.service.NotificationStateMachine
import dev.dshmobile.service.NotificationStateMachine.Command
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 通知状态机全分支单测（设计 §8：冷启动抑制 / 断连重连补发 / cancel 抑制窗 /
 * subagent 过滤 / approvalId·rpcId 去重 replay 不重响 / running 恢复撤通知）。
 *
 * 时钟注入 → 全部分支确定性验证（手写对照组：每个用例列出预期指令序列）。
 */
class TurnNotificationStateMachineTest {

    /** 可手动推进的假时钟。 */
    private class FakeClock(var now: Long = 1_000_000L) {
        fun advance(ms: Long) { now += ms }
        operator fun invoke(): Long = now
    }

    private lateinit var clock: FakeClock
    private lateinit var machine: NotificationStateMachine

    private fun summary(
        id: String,
        running: Boolean,
        origin: String? = null,
        cwd: String? = "D:\\proj",
        title: String? = null,
    ): SessionSummary = SessionSummary(
        sessionId = id,
        updatedAt = 1.0,
        running = running,
        blank = false,
        origin = origin,
        cwd = cwd,
        projections = if (title != null) {
            dev.dshmobile.model.ProjectionsBlock(0, kotlinx.serialization.json.buildJsonObject {
                put("title", title)
            })
        } else null,
    )

    private fun statusFrame(id: String, running: Boolean) =
        HostFrame.SessionStatus(sessionId = id, running = running)

    private fun approvalFrame(sessionId: String, approvalId: String) = MuxFrame.ApprovalRequested(
        sessionId = sessionId, approvalId = approvalId, toolName = "pwsh", reason = "install",
    )

    private fun questionFrame(sessionId: String) = MuxFrame.QuestionRequested(
        sessionId = sessionId,
        questions = listOf(QuestionItem(id = "q1", question = "继续吗？")),
    )

    // -------------------------------------------------------------------
    // 回合完成通知核心分支
    // -------------------------------------------------------------------

    @Test
    fun `cold start baseline is silent even for sessions that finished during downtime`() {
        clock = FakeClock(); machine = NotificationStateMachine { clock() }
        // 首次 baseline：内存无快照（prev==null 场景的批量形态）→ 静默
        val cmds = machine.onBaseline(listOf(summary("s1", running = false)))
        // 唯一允许的指令是 running 分支的幂等撤通知；不得出现 ShowTurnComplete
        assertTrue(cmds.none { it is Command.ShowTurnComplete })
    }

    @Test
    fun `running true then false via host frames notifies turn complete`() {
        clock = FakeClock(); machine = NotificationStateMachine { clock() }
        machine.onBaseline(listOf(summary("s1", running = true, title = "修BUG")))
        val start = machine.onHostFrame(statusFrame("s1", running = true))
        // 启动运行：撤通知（幂等）
        assertEquals(1, start.count { it is Command.CancelTurnComplete })
        val done = machine.onHostFrame(statusFrame("s1", running = false))
        val show = assertIs<Command.ShowTurnComplete>(done.single())
        assertEquals("s1", show.sessionId)
        assertEquals("修BUG", show.displayTitle)
    }

    @Test
    fun `running resume cancels turn notification and records local since`() {
        clock = FakeClock(); machine = NotificationStateMachine { clock() }
        machine.onBaseline(listOf(summary("s1", running = true)))
        machine.onHostFrame(statusFrame("s1", running = true))
        machine.onHostFrame(statusFrame("s1", running = false))   // → Show
        val resume = machine.onHostFrame(statusFrame("s1", running = true))
        // 恢复运行：撤通知；快照记录本地翻转时刻
        assertTrue(resume.any { it is Command.CancelTurnComplete })
        val snapshot = machine.sessionSnapshot()["s1"]!!
        assertEquals(clock.now, snapshot.localRunningSinceMillis)
    }

    @Test
    fun `reconnect baseline re-notifies sessions that finished inside the disconnect window`() {
        // P1 修正核心用例：断连窗口内完成的回合必须补发（一刀切抑制=永久漏报）
        clock = FakeClock(); machine = NotificationStateMachine { clock() }
        machine.onBaseline(listOf(summary("s1", running = true)))
        machine.onHostFrame(statusFrame("s1", running = true))
        // 模拟断连期间回合结束：重连后 baseline 显示 running=false
        val cmds = machine.onBaseline(listOf(summary("s1", running = false)))
        assertTrue(cmds.any { it is Command.ShowTurnComplete })
    }

    @Test
    fun `reconnect baseline stays silent for still-running and never-run sessions`() {
        clock = FakeClock(); machine = NotificationStateMachine { clock() }
        machine.onBaseline(listOf(summary("run", running = true), summary("idle", running = false)))
        val cmds = machine.onBaseline(listOf(summary("run", running = true), summary("idle", running = false)))
        assertTrue(cmds.none { it is Command.ShowTurnComplete })
    }

    @Test
    fun `subagent session never triggers turn complete notification`() {
        clock = FakeClock(); machine = NotificationStateMachine { clock() }
        machine.onBaseline(listOf(summary("sub", running = true, origin = "subagent")))
        machine.onHostFrame(statusFrame("sub", running = true))
        val done = machine.onHostFrame(statusFrame("sub", running = false))
        assertTrue(done.none { it is Command.ShowTurnComplete })
        // 断连补发路径同样过滤（§5.2 P1 注释：不过滤则通知轰炸在此复活）
        machine.onBaseline(listOf(summary("sub2", running = true, origin = "subagent")))
        machine.onHostFrame(statusFrame("sub2", running = true))
        val reconnect = machine.onBaseline(listOf(summary("sub2", running = false, origin = "subagent")))
        assertTrue(reconnect.none { it is Command.ShowTurnComplete })
    }

    @Test
    fun `local cancel opens 3s suppress window then closes`() {
        clock = FakeClock(); machine = NotificationStateMachine { clock() }
        machine.onBaseline(listOf(summary("s1", running = true)))
        machine.onHostFrame(statusFrame("s1", running = true))
        machine.onLocalCancel("s1")
        // 窗内到达的 running:false（cancel 的副作用）不触发完成通知
        val inWindow = machine.onHostFrame(statusFrame("s1", running = false))
        assertTrue(inWindow.none { it is Command.ShowTurnComplete })
        // 窗后正常翻转仍通知（重跑场景）
        machine.onHostFrame(statusFrame("s1", running = true))
        clock.advance(NotificationStateMachine.CANCEL_SUPPRESS_WINDOW_MILLIS + 1)
        val afterWindow = machine.onHostFrame(statusFrame("s1", running = false))
        assertTrue(afterWindow.any { it is Command.ShowTurnComplete })
    }

    @Test
    fun `running true clears blank flag so new session becomes visible in list`() {
        // bug：新建会话发出首条消息后（running=true），快照 blank 仍为 true →
        // 列表一直隐藏该会话（baseline 未重拉时永不自愈）。修复：running ⇒ 不再 blank。
        clock = FakeClock(); machine = NotificationStateMachine { clock() }
        machine.onBaseline(listOf(summary("new1", running = false).copy(blank = true)))
        check(machine.sessionSnapshot()["new1"]!!.blank)   // 前置：新建未跑时确实 blank
        machine.onHostFrame(statusFrame("new1", running = true))
        assertTrue(!machine.sessionSnapshot()["new1"]!!.blank)
        // 回合结束后仍不 blank（一旦开跑就永久可见，不再退回隐藏）
        machine.onHostFrame(statusFrame("new1", running = false))
        assertTrue(!machine.sessionSnapshot()["new1"]!!.blank)
    }

    @Test
    fun `baseline cannot latch blank back to true once cleared`() {
        // 协作升级加固：blank 单向锁存——running 清过 blank 后，乱序/滞后 baseline（blank=true）
        // 不得把会话打回隐藏（镜像宿主 applySessionListMetadata 单调语义）
        clock = FakeClock(); machine = NotificationStateMachine { clock() }
        machine.onBaseline(listOf(summary("s1", running = false).copy(blank = true)))
        machine.onHostFrame(statusFrame("s1", running = true))   // 转正
        // 滞后快照仍说 blank=true → 不得覆盖回
        machine.onBaseline(listOf(summary("s1", running = false).copy(blank = true)))
        assertTrue(!machine.sessionSnapshot()["s1"]!!.blank)
        // 宿主权威确认 blank=false 的快照照常通过
        machine.onBaseline(listOf(summary("s1", running = false)))
        assertTrue(!machine.sessionSnapshot()["s1"]!!.blank)
    }

    @Test
    fun `session added frame merges instead of replacing existing state`() {
        // 协作升级加固：session/added 整体替换会丢 running/已锁存的 blank=false（重放/乱序时
        // 把已转正会话打回隐藏）。合并语义：已有状态优先，帧只补缺。
        clock = FakeClock(); machine = NotificationStateMachine { clock() }
        machine.onBaseline(listOf(summary("s1", running = false).copy(blank = true)))
        machine.onHostFrame(statusFrame("s1", running = true))   // 转正：running=true, blank=false
        // session/added 重放（blank=true）→ running 与 blank 均保留
        machine.onHostFrame(HostFrame.SessionAdded(sessionId = "s1", blank = true, cwd = "D:\\x"))
        val state = machine.sessionSnapshot()["s1"]!!
        assertTrue(state.running)
        assertTrue(!state.blank)
        assertTrue(state.updatedAt > 0.0)   // baseline 的 updatedAt 不被清零
    }

    @Test
    fun `status frame for unknown session is recorded silently`() {
        // prev==null：不评估通知，仅记录状态（防首帧先于 baseline 的竞态崩溃）
        clock = FakeClock(); machine = NotificationStateMachine { clock() }
        val cmds = machine.onHostFrame(statusFrame("ghost", running = false))
        assertTrue(cmds.none { it is Command.ShowTurnComplete })
        assertEquals(false, machine.sessionSnapshot()["ghost"]!!.running)
    }

    // -------------------------------------------------------------------
    // 审批分支
    // -------------------------------------------------------------------

    @Test
    fun `approval requested notifies and replay updates silently`() {
        clock = FakeClock(); machine = NotificationStateMachine { clock() }
        machine.onBaseline(listOf(summary("s1", running = false)))
        val first = machine.onMuxFrame("rpc-1", approvalFrame("s1", "a1"))
        val show = assertIs<Command.ShowApproval>(first.single())
        assertEquals(false, show.replay)
        assertEquals("a1", show.info.approvalId)
        assertEquals("rpc-1", show.info.rpcId)
        // mux 重连 replay 同 approvalId（rpcId 复用）：静默更新不重响
        val replay = machine.onMuxFrame("rpc-1", approvalFrame("s1", "a1"))
        val replayShow = assertIs<Command.ShowApproval>(replay.single())
        assertEquals(true, replayShow.replay)
        // 不同 approvalId 是新审批
        val second = machine.onMuxFrame("rpc-2", approvalFrame("s1", "a2"))
        assertEquals(false, (second.single() as Command.ShowApproval).replay)
    }

    @Test
    fun `approval resolved cancels notification idempotently`() {
        clock = FakeClock(); machine = NotificationStateMachine { clock() }
        machine.onBaseline(listOf(summary("s1", running = false)))
        machine.onMuxFrame("rpc-1", approvalFrame("s1", "a1"))
        val resolved = machine.onMuxFrame(
            "rpc-x", MuxFrame.ApprovalResolved(sessionId = "s1", approvalId = "a1", outcome = "allowed-once")
        )
        assertIs<Command.CancelApproval>(resolved.single())
        // 重复 resolved（别处已处理后再收 replay）：撤通知幂等，不崩
        val again = machine.onMuxFrame(
            "rpc-x", MuxFrame.ApprovalResolved(sessionId = "s1", approvalId = "a1", outcome = "allowed-once")
        )
        assertIs<Command.CancelApproval>(again.single())
    }

    @Test
    fun `approval for subagent session is skipped`() {
        clock = FakeClock(); machine = NotificationStateMachine { clock() }
        machine.onBaseline(listOf(summary("sub", running = false, origin = "subagent")))
        val cmds = machine.onMuxFrame("rpc-1", approvalFrame("sub", "a1"))
        assertTrue(cmds.isEmpty())
    }

    // -------------------------------------------------------------------
    // 提问分支
    // -------------------------------------------------------------------

    @Test
    fun `question requested notifies keyed by envelope rpcId and replay updates silently`() {
        clock = FakeClock(); machine = NotificationStateMachine { clock() }
        machine.onBaseline(listOf(summary("s1", running = false)))
        val first = machine.onMuxFrame("qrpc-1", questionFrame("s1"))
        val show = assertIs<Command.ShowQuestion>(first.single())
        assertEquals(false, show.replay)
        assertEquals("继续吗？", show.info.firstQuestionText)
        val replay = machine.onMuxFrame("qrpc-1", questionFrame("s1"))
        assertEquals(true, (replay.single() as Command.ShowQuestion).replay)
        // 不同 rpcId = 新提问
        val second = machine.onMuxFrame("qrpc-2", questionFrame("s1"))
        assertEquals(false, (second.single() as Command.ShowQuestion).replay)
    }

    @Test
    fun `question resolved cancels by question rpc id`() {
        clock = FakeClock(); machine = NotificationStateMachine { clock() }
        machine.onBaseline(listOf(summary("s1", running = false)))
        machine.onMuxFrame("qrpc-1", questionFrame("s1"))
        val resolved = machine.onMuxFrame(
            "x", MuxFrame.QuestionResolved(sessionId = "s1", questionRpcId = "qrpc-1", outcome = "answered")
        )
        assertIs<Command.CancelQuestion>(resolved.single())
    }

    // -------------------------------------------------------------------
    // 投影标题 / 会话增删
    // -------------------------------------------------------------------

    @Test
    fun `title projection updates display title of later notifications`() {
        clock = FakeClock(); machine = NotificationStateMachine { clock() }
        machine.onBaseline(listOf(summary("s1", running = true, cwd = "D:\\work\\myproj")))
        machine.onHostFrame(statusFrame("s1", running = true))
        // 无标题时用 cwd 尾段
        val before = machine.onHostFrame(statusFrame("s1", running = false))
        assertEquals("myproj", (before.single() as Command.ShowTurnComplete).displayTitle)
        // 投影更新标题后用标题
        machine.onHostFrame(statusFrame("s1", running = true))
        machine.onMuxFrame(
            "x", MuxFrame.SessionProjection(sessionId = "s1", key = "title", value = JsonPrimitive("新标题"), seq = 5)
        )
        val after = machine.onHostFrame(statusFrame("s1", running = false))
        assertEquals("新标题", (after.single() as Command.ShowTurnComplete).displayTitle)
    }

    @Test
    fun `session added records origin and removed clears state`() {
        clock = FakeClock(); machine = NotificationStateMachine { clock() }
        machine.onHostFrame(HostFrame.SessionAdded(sessionId = "new", blank = false, origin = "subagent", cwd = "D:\\x"))
        assertEquals("subagent", machine.sessionSnapshot()["new"]!!.origin)
        machine.onHostFrame(HostFrame.SessionRemoved(sessionId = "new"))
        assertEquals(null, machine.sessionSnapshot()["new"])
    }

    @Test
    fun `baseline clears sessions missing from authoritative list`() {
        // 终审 P2-10：断连窗口内被删的会话，重连 baseline 后必须清除（防幽灵）
        clock = FakeClock(); machine = NotificationStateMachine { clock() }
        machine.onBaseline(listOf(summary("keep", running = true), summary("gone", running = false)))
        assertTrue(machine.sessionSnapshot().containsKey("gone"))
        // 重连后 session.list 不再包含 gone → 从快照清除
        machine.onBaseline(listOf(summary("keep", running = true)))
        assertTrue(machine.sessionSnapshot().containsKey("keep"))
        assertEquals(null, machine.sessionSnapshot()["gone"])
    }

    // -------------------------------------------------------------------
    // 其他 host/mux 帧不打扰通知域
    // -------------------------------------------------------------------

    @Test
    fun `workspace agent-error and stream frames produce no notification commands`() {
        clock = FakeClock(); machine = NotificationStateMachine { clock() }
        val frames = listOf(
            HostFrame.AgentError(sessionId = "s1", message = "boom"),
            HostFrame.WorkspaceRemoved(workspaceId = "w1"),
            HostFrame.WorkspaceOrderChanged(workspaceIds = listOf("w1")),
            HostFrame.ArchivedSessionsChanged(archivedSessionIds = listOf("s9")),
            HostFrame.RemoteEvent(event = "e", args = kotlinx.serialization.json.JsonArray(emptyList())),
            HostFrame.StreamError(error = dev.dshmobile.model.RpcError("internal", "x")),
            HostFrame.Unknown("host/future"),
        )
        for (frame in frames) {
            assertEquals(emptyList(), machine.onHostFrame(frame), "frame $frame should be inert")
        }
        val muxFrames = listOf<MuxFrame>(
            MuxFrame.SessionEvent(sessionId = "s1", event = kotlinx.serialization.json.buildJsonObject { }),
            MuxFrame.SessionSubscribed(sessionId = "s1", lastSeq = 1),
            MuxFrame.SessionQueue(sessionId = "s1", items = kotlinx.serialization.json.JsonArray(emptyList())),
            MuxFrame.SessionJobs(sessionId = "s1", jobs = kotlinx.serialization.json.JsonArray(emptyList())),
            MuxFrame.StreamError(dev.dshmobile.model.RpcError("internal", "x")),
            MuxFrame.Unknown("future/frame"),
        )
        for (frame in muxFrames) {
            assertEquals(emptyList(), machine.onMuxFrame("r", frame), "frame $frame should be inert")
        }
    }
}
