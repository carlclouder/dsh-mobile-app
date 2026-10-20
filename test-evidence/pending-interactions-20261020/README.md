# 交互缺失与消息排队修复验收

日期：2026-10-20。App：0.1.187（188）。真实主机：dsh 0.2.0-rc.2。设备：本地 dsh_test 模拟器，Android 15，1080×2400。

## 实际闭环

1. 在隔离测试会话发起真实 `ask_user_question`，等待期间才安装最新 App。
2. App 显示问题、两个选项及说明，并明确后续普通消息会排队。
3. 在 App 输入并发送 `Reply only MOBILE_QUEUE_OK after the pending question.`，页面显示“已加入队列”。
4. 在 App 选择“继续测试”并提交。提问卡撤下，工具收到精确答案，依次显示 `MOBILE_INTERACTION_OK` 和 `MOBILE_QUEUE_OK`。
5. 独立读取测试会话：两回合正常结束，最终 `running=false`、`inbox.next-turn=[]`、`inbox.next-step=[]`。

这证明修复后的 App 能收到已存在的交互、通过真实接口作答，并解除后续消息的等待。未触碰用户原有会话的选择和队列。

## GIF 与原图

| 文件 | 实际内容 |
| --- | --- |
| [queue-interaction.gif](queue-interaction.gif) | 24 帧，800ms；已有提问 → App 输入并发送 → 消息显示已加入队列 |
| [answer-interaction.gif](answer-interaction.gif) | 36 帧，800ms；未选择 → 选择继续测试 → 提交 → 提问和后续消息均完成 |
| [before-question.png](before-question.png) | 初始提问，含选项说明和等待原因 |
| [message-queued.png](message-queued.png) | App 发送的消息显示已加入队列 |
| [answer-selected.png](answer-selected.png) | 选中状态明确可见 |
| [after-completed.png](after-completed.png) | 提问卡已撤下，两个完成标记可见 |
| [queue-capture.json](queue-capture.json)、[answer-capture.json](answer-capture.json) | 60 次真实采样的时刻和原始 PNG 哈希 |
| [host-result.json](host-result.json) | 真实主机工具结果、完成消息及空队列，排除其他会话内容 |

所有画面来自 `adb exec-out screencap -p`，没有补画或拼接状态。GIF 按顺序缩放至 540×1200，逐帧保存，未合并重复帧。采样间隔实测 799–801ms；GIF 每帧固定 800ms。

独立查看全部 60 帧通过：排队 GIF 初始至少 4 帧、结束至少 9 帧稳定；回答 GIF 初始至少 4 帧、选择阶段至少 2 帧、结束至少 24 帧稳定；操作过渡连续。仓库保存 GIF、四张关键原图和逐帧哈希记录。

## 需求场景与验证结果

| 需求场景 | 实际验证入口 | 结果 |
| --- | --- | --- |
| 已存在的提问在 App 可见 | 真实主机 + 最新 App；before-question.png | 通过 |
| 等待交互时普通消息入队 | 真实 App 发送；queue-interaction.gif | 通过 |
| App 回答解除等待，后续消息继续 | answer-interaction.gif + host-result.json | 通过 |
| 无限/限时等待、未知事件、本机撤卡 | DshInteractionStreamTest，4 项真实 WebSocket/HTTP 测试 | 通过 |
| 现代/旧回答接口、返回值和失败 | InteractionApiClientTest，20 项；既有 DshApiClientTest | 通过 |
| 重连、取消、投影、稍后回答与换主机 | InteractionStateMachineTest，12 项 | 通过 |
| 原有发送、历史、统计及解析 | testDebugUnitTest 全量 150 项 | 150 通过，0 失败、0 错误、0 跳过 |
| Debug/Release 构建和发布签名 | assembleDebug assembleRelease、apksigner、aapt | 通过，0.1.187（188） |

主机实际历史：seq19 收到 `{"answers":[{"id":"mobile-gif-choice","selected":["继续测试"]}]}`；seq23 为首个完成标记，seq29 为后续用户消息，seq31 为第二个完成标记，seq33 完成第二回合。

## 构建与边界

构建使用项目现有 JDK17、Gradle 8.9 和 Android SDK35。首次构建因系统临时目录权限失败，改用已有 `.gradle/.tmp` 后构建成功；未修改系统目录权限。

发布包：`app/build/outputs/apk/release/DSH-Mobile-release-v0.1.187.apk`。
SHA256：`8ee1070fd23912f0a4728aea549451dfdba265c0b2baef7aa522bccc0a56d95b`。

本次真实设备只覆盖主机当前的无限等待模式。限时到期、稍后回答、跨端先回答、断线草稿恢复和通知按钮的完整设备路径未验证；相应协议与状态机测试、源码审查通过。真机和 Release 设备运行未验证。

设备日志没有 FATAL EXCEPTION；存在 Markdown/AppCompat 主题提示和输入法动画计时提示，本次提问、发送、回答与队列执行均正常。未将此日志描述为全量无错误。
