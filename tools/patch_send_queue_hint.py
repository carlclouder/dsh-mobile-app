"""给 ConversationViewModel.send 增加"运行中→已排队"的用户反馈。

背景（2026-09-16 手机实测报障）：会话运行中发送消息时，dsh 的新版契约
`session/prompt`（mode="queue"，与官方 WebUI 发消息完全一致）会把消息排入队列，
等当前回合结束后才执行。App 之前不提示排队，用户看到消息既没被 agent 处理、
WebUI 的队列区又出现该消息，误判为"并未真正发送"。
本补丁在发送受理成功且发送前会话处于运行中时，给出明确的排队提示。
"""
import sys

path = r"D:\AI任务\dsh-mobile-app\app\src\main\java\dev\dshmobile\ui\ConversationViewModel.kt"
text = open(path, encoding="utf-8").read()

old = '''    fun send(text: String) {
        if (text.isBlank()) return
        _running.value = true'''

new = '''    fun send(text: String) {
        if (text.isBlank()) return
        // 发送前的运行态：true = 当前回合正在跑，按 dsh 新版契约（session/prompt，mode=queue，
        // 与官方 WebUI 发消息一致）这条消息会**排入队列**、等回合结束后自动执行。
        // 记录它以在受理成功后给用户明确反馈（否则用户会误判"没发送"）。
        val wasRunning = _running.value
        _running.value = true'''

if old not in text:
    print("PATTERN-1 NOT FOUND")
    sys.exit(1)
text = text.replace(old, new, 1)

old2 = '''                is ApiResult.Ok -> {
                    // 发送受理即标记"本机已发过消息"（本地事实）：列表立即豁免 blank 隐藏——
                    // 不等宿主 turn/start（数秒延迟）与推送帧（慢链路可能丢），返回列表即刻可见
                    DshRepository.markSessionPrompted(sessionId)
                }'''

new2 = '''                is ApiResult.Ok -> {
                    // 发送受理即标记"本机已发过消息"（本地事实）：列表立即豁免 blank 隐藏——
                    // 不等宿主 turn/start（数秒延迟）与推送帧（慢链路可能丢），返回列表即刻可见
                    DshRepository.markSessionPrompted(sessionId)
                    if (wasRunning) {
                        // 排队反馈（手机实测报障修复）：消息已受理但会等当前回合结束才执行
                        _actionError.value = "已加入队列：当前回合运行中，这条消息会在回合结束后自动发送。"
                    }
                }'''

if old2 not in text:
    print("PATTERN-2 NOT FOUND")
    sys.exit(1)
text = text.replace(old2, new2, 1)

with open(path, "w", encoding="utf-8", newline="") as f:
    f.write(text)
print("patched ok")
