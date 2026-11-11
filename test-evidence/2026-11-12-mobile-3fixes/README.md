# 2026-11-12 手机端三处缺陷修复验收（0.1.222）

## 背景（用户反馈原文）

- "3、4 依然没修复；3、退回瞬间存在，过几秒后消失；4、模型和思考等级依然为空 没有和webui对应"（2026-11-11）
- "app出现大量等待输入的会话，大部分已归档或早已过时，严重干扰使用，和webui行为没对齐"（2026-11-11）
- 交付要求："修完本地测试清楚 别没修却瞎说修完了，gif证据"（2026-11-11）

## 根因与修复

### 1. 新建会话返回列表后数秒消失

- 宿主实测：`session/create` 返回 `blank:true` 的空会话，首次出现在 `session/list`，约 10 秒后宿主自动裁掉（t0 在列表、t+12s 与 t+42s 已不在）。
- App 侧：`NotificationStateMachine.onBaseline` 末尾 `sessions.keys.retainAll(liveIds)` 把宿主不再返回的会话直接删除，界面行随之消失。
- 修复（`service/NotificationStateMachine.kt`、`service/EventStreamService.kt`）：
  - `onBaseline(items, keepSessionIds)` 接收本地创建集合，末尾把"宿主缺席但仍是 blank 的本地新建会话"并入 `liveIds` 再裁剪。
  - `SessionRemoved` 帧也只对"本地新建且仍 blank"的会话豁免，已转正或已归档的照常删除，避免留下幽灵行。
  - 本地创建记录通过 `DshRepository.locallyCreatedSessions` 流实时注入状态机，进程重启后仍生效。

### 2. 大量会话误进"等你输入"

- 旧判据：`updatedAt > 0 && now - updatedAt < 7 天` 即归入"等你输入"。实测 217 条会话里 134 条都满足，与"是否等用户输入"无关。
- 修复（`ui/SessionListScreen.kt`）：只有该会话存在挂起审批（`pendingApprovals`）或挂起提问（`pendingQuestions`）才进"等你输入"；运行中单独成组；其余一律"空闲"。

### 3. 模型 / 思考等级面板为空

- 宿主 `session/modelCatalog` 正常返回 `default={provider:"deepseek-official",model:"deepseek-flash",reasoningEffort:"high"}` 与两组模型目录；问题在 App 侧展示链路缺兜底。
- 修复（`ui/ConversationViewModel.kt`、`ui/ConversationActivity.kt`）：
  - 连接恢复且目录为空时自动重新加载；打开模型面板时若目录为空也自动重载。
  - 当前会话无 `modelSelection` 时用目录 `default` 兜底；思考等级为空时用该模型 `reasoning.defaultEffort` 兜底。
  - 顶栏 chip 从裸 `provider/model` 改为"模型显示名 · 思考等级"。

## 单测

`gradle :app:testDebugUnitTest`：228 项全部通过（16 个套件，failures=0、errors=0）。

新增 `app/src/test/java/dev/dshmobile/SessionListBehaviorTest.kt` 6 项：本机新建会话在宿主裁掉后仍保留、已转正会话不再豁免、例外只对本机记录生效、移除帧不删本机新建但删普通会话、等你输入只看挂起审批或提问、挂起提问同样算等你输入。

## 运行态验收（emulator-5554，App 连 http://10.0.2.2:3081，0.1.222 debug）

| 验收点 | 结果 | 证据 |
|---|---|---|
| 列表不再出现"等你输入"分组 | "跑动中 1 / 空闲 140"，无第三组 | `03-list-after-20s.png`、`01-session-create-persist.gif` |
| 新建会话返回后 20 秒仍在 | 新行"dsh-mobile-app · 刚刚"居首，空闲数 +1 且不再回落 | `01-session-create-persist.gif`、`03-list-after-20s.png` |
| 重装 APK 后新建会话仍在 | 多次覆盖安装，之前新建的 4–15 分钟前会话全部保留 | `03-list-after-20s.png` |
| 顶栏模型与思考等级 | `◆ DeepSeek-V4.1-Flash · High` | `04-chip-aligned.png` |
| 模型面板有内容 | DeepSeek / DeepSeek Account 两组、V4.1-Flash 选中 | `02-model-picker.gif`、`05-model-groups.png` |
| 思考等级有内容 | Off / Low / High（选中）/ Max | `02-model-picker.gif`、`06-reasoning-levels.png` |

## 未覆盖 / 限制

- 仅模拟器（emulator-5554）验证，未上真机。
- 归档集合仍来自 `/api/remote.mux` 的 `workspace/follow` 流，跨设备归档同步未验证。
- "等你输入"分组依赖真实的挂起审批/挂起提问，本次运行态列表中没有待办会话，该分组只由单测覆盖（构造 pendingApprovals / pendingQuestions 验证）。
- 录屏素材 `01` 由 `adb shell screenrecord` 录制后 ffmpeg 转 GIF（fps=6，420 宽）；`02` 只覆盖"打开面板 → 滚动到 High/Max"约 11 秒。
