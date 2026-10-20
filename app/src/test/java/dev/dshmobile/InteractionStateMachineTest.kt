package dev.dshmobile

import dev.dshmobile.model.InteractionTarget
import dev.dshmobile.model.QuestionItem
import dev.dshmobile.model.SessionSummary
import dev.dshmobile.service.NotificationStateMachine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 使用宿主交互轨迹验证实际状态机；预期由用户可见的卡片生命周期决定。 */
class InteractionStateMachineTest {
    private val machine = NotificationStateMachine()
    private val first = InteractionTarget.Remote("连接一", "事件一")

    private fun question(target: InteractionTarget.Remote = first, callId: String? = "调用一") =
        NotificationStateMachine.QuestionInfo(
            target.eventId, "会话一", "是否继续？",
            listOf(QuestionItem("问题一", "是否继续？", detail = "请检查此说明后再选择。")),
            target = target, callId = callId,
        )

    private fun snapshot(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject
    private fun continued(inbox: String = "{}") = snapshot("""
        {"userQuestions":{"active":[{"callId":"调用一","state":"continued",
          "questions":[{"id":"问题一","question":"是否继续？"}]}]},"inbox":$inbox}
    """.trimIndent())

    @Test fun `现代审批不被历史子会话来源过滤且重放静默`() {
        machine.onBaseline(listOf(SessionSummary("会话一", 0.0, true, false, origin = "subagent")))
        val info = NotificationStateMachine.ApprovalInfo("事件一", "会话一", "事件一", "执行命令", null, first)
        assertFalse(assertIs<NotificationStateMachine.Command.ShowApproval>(machine.onRemoteApproval(info).single()).replay)
        assertTrue(assertIs<NotificationStateMachine.Command.ShowApproval>(machine.onRemoteApproval(info).single()).replay)
        assertEquals(info, machine.snapshotApprovals()["会话一"]?.single())
    }

    @Test fun `断线立即停用回答地址并保留原问题`() {
        machine.onRemoteQuestion(question())
        machine.onRemoteDisconnected("连接一")
        val pending = machine.snapshotQuestions()["会话一"]!!.single()
        assertFalse(pending.available)
        assertNull(pending.target)
        assertEquals("会话一:调用一", pending.key)
        assertEquals("请检查此说明后再选择。", pending.questions.single().detail)
    }

    @Test fun `旧连接取消不得删除新连接重放的目标`() {
        machine.onRemoteQuestion(question())
        machine.onRemoteDisconnected("连接一")
        machine.onRemoteReady()
        val replacement = InteractionTarget.Remote("连接二", "事件一")
        machine.onRemoteQuestion(question(replacement))
        machine.onRemoteCancelled(first)
        machine.onRemoteDisconnected("连接一")
        val pending = machine.snapshotQuestions()["会话一"]!!.single()
        assertEquals(replacement, pending.target)
        assertTrue(pending.available)
    }

    @Test fun `非限时问题在取消后移除`() {
        machine.onRemoteQuestion(question(callId = null))
        machine.onRemoteCancelled(first)
        assertTrue(machine.snapshotQuestions().isEmpty())
    }

    @Test fun `限时问题取消后可转稍后回答且保留详情与稳定键`() {
        val original = question()
        machine.onRemoteQuestion(original)
        machine.onRemoteCancelled(first)
        machine.onQuestionProjection("会话一", continued(), 10)
        val pending = machine.snapshotQuestions()["会话一"]!!.single()
        assertEquals(original.key, pending.key)
        assertEquals(InteractionTarget.Continued("会话一", "调用一"), pending.target)
        assertEquals(original.questions.single().detail, pending.questions.single().detail)
        assertTrue(pending.available)
    }

    @Test fun `收件箱已有回答时禁止重复提交`() {
        val inbox = """{"next-turn":[{"source":{"kind":"user-question-reply","callId":"调用一"}}]}"""
        machine.onQuestionProjection("会话一", continued(inbox), 10)
        val pending = machine.snapshotQuestions()["会话一"]!!.single()
        assertTrue(pending.answerQueued)
        assertFalse(pending.available)
    }

    @Test fun `缺投影和畸形投影不清除挂起问题`() {
        machine.onRemoteQuestion(question())
        machine.onQuestionProjection("会话一", snapshot("{}"), 50)
        machine.onQuestionProjection("会话一", snapshot("""{"userQuestions":{"active":[{}]}}"""), 50)
        assertEquals(first, machine.snapshotQuestions()["会话一"]!!.single().target)
    }

    @Test fun `迟到投影不撤销较新问题且不影响其他会话`() {
        machine.onQuestionProjection("会话一", continued(), 20)
        machine.onQuestionProjection("会话二", snapshot("""{"userQuestions":{"active":[]}}"""), 99)
        machine.onQuestionProjection("会话一", snapshot("""{"userQuestions":{"active":[]}}"""), 19)
        assertTrue(machine.snapshotQuestions().containsKey("会话一"))
    }

    @Test fun `宿主确认已回答后撤除稍后回答卡`() {
        machine.onQuestionProjection("会话一", continued(), 20)
        machine.onContinuedSubmitted(InteractionTarget.Continued("会话一", "调用一"))
        assertTrue(machine.snapshotQuestions()["会话一"]!!.single().answerQueued)
        machine.onQuestionProjection("会话一", snapshot("""{"userQuestions":{"active":[]},"inbox":{}}"""), 21)
        assertTrue(machine.snapshotQuestions().isEmpty())
    }

    @Test fun `打开状态的旧投影不覆盖活动回答地址`() {
        machine.onRemoteQuestion(question())
        val open = continued().toString().replace("continued", "open")
        machine.onQuestionProjection("会话一", snapshot(open), 10)
        assertEquals(first, machine.snapshotQuestions()["会话一"]!!.single().target)
    }

    @Test fun `已受理回答不被同游标旧快照复活但删除队列后可再次回答`() {
        val emptyInbox = """{"next-step":[],"next-turn":[]}"""
        machine.onQuestionProjection("会话一", continued(emptyInbox), 20)
        machine.onContinuedSubmitted(InteractionTarget.Continued("会话一", "调用一"))
        machine.onQuestionProjection("会话一", continued(emptyInbox), 20)
        assertTrue(machine.snapshotQuestions()["会话一"]!!.single().answerQueued)
        // 更大游标证明是新的权威状态，两类队列均为空才可恢复答题。
        machine.onQuestionProjection("会话一", continued(emptyInbox), 21)
        val restored = machine.snapshotQuestions()["会话一"]!!.single()
        assertFalse(restored.answerQueued)
        assertTrue(restored.available)
    }

    @Test fun `换宿主清旧交互及游标允许新宿主低游标状态`() {
        machine.onInteractionHostGeneration(1)
        machine.onQuestionProjection("会话一", continued(), 100, 1)
        machine.onInteractionHostGeneration(2)
        assertTrue(machine.snapshotQuestions().isEmpty())
        machine.onQuestionProjection("会话一", continued(), 2, 2)
        assertEquals(InteractionTarget.Continued("会话一", "调用一", 2),
            machine.snapshotQuestions()["会话一"]!!.single().target)
    }
}
