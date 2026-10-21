# DSH Mobile —— 系统架构设计方案

> 本文是对 `docs/DESIGN.md`（详细设计）的架构层面总览，聚焦「分层、模块、数据流、关键决策」，
> 便于快速理解整体结构。详细协议与评审修正溯源见 DESIGN.md。

## 1. 系统定位与上下文

DSH Mobile 是一个安卓原生客户端，通过 Tailscale 隧道远程访问 PC 上运行的
DeepSeek Harness（`dsh web`，监听 127.0.0.1:3080）。

```
手机 App (Android) ── Tailscale(HTTPS) ──> PC 上的 dsh host
    (原生客户端)                              (免令牌网关插件 :3081 → dsh web :3080)
```

> **注（2026-09-16）**：dsh ≥0.1.2-rc.1 引入链接令牌认证后，App 不再直连 3080，而是经宿主侧
> **免令牌网关插件**（`dsh-auth-gateway`，跑在 dsh 进程内的反向代理，自动注入会话 Cookie）：
> `Tailscale Serve → 127.0.0.1:3081（网关）→ 127.0.0.1:3080（dsh）`。
> 因此 App 只需配置**裸地址**（`https://<机器名>.<tailnet>.ts.net/`）。
> 插件设计与验证见 `docs/dsh-auth-gateway-plugin.md`；App 侧协议适配见 `docs/DESIGN.md` §13。
> 同时，新版移除了 WebSocket 事件流端点，App 的实时性改由**周期轮询**提供（会话列表 3 秒、
> 会话页运行中 2 秒）。

三大核心诉求：
1. **会话完成通知**：agent 回合结束等待输入时弹系统通知，APP 退后台/息屏也能收到。
2. **多会话快速处理**：原生会话列表，按「跑动中 / 等你输入 / 空闲」三态分组。
3. **移动端排版**：界面按手机屏幕设计。

约束：单机使用（无配对机制）、v1 不改服务端、界面中文、不做模型配置页/文件上传/语音等。

## 2. 技术栈

| 维度 | 选型 |
|------|------|
| 语言 | Kotlin（JVM 17） |
| UI | Jetpack Compose + Material3 |
| 架构 | 分层 + MVVM + 单向数据流 |
| 网络 | OkHttp（HTTP RPC 与周期对账；`remote.mux` 承载工作区订阅、现代交互事件和限时等待） |
| 序列化 | kotlinx-serialization-json |
| 并发 | kotlinx-coroutines（Flow/StateFlow/SharedFlow） |
| 持久化 | DataStore<Preferences>（设置）+ SharedPreferences（会话 Cookie，30 天复用） |
| 平台 | minSdk 26 / targetSdk 35 |

## 3. 架构风格与分层

采用 **分层 + MVVM + 单例状态源（Repository Pattern）**，各层单向依赖：

```
┌────────────────────────────────────────────────────────┐
│ UI 层 (Jetpack Compose)                                 │
│  MainActivity · SessionListScreen · SettingsScreen      │
│  ConversationActivity · Theme · ThinkingStreamRow       │
└───────────────────────────┬────────────────────────────┘
                            │ 订阅 StateFlow / 触发动作
┌───────────────────────────▼────────────────────────────┐
│ ViewModel 层                                            │
│  ConversationViewModel（对话/流式/审批/提问/队列/模型）   │
│  RespondReceiptPolicy（应答回执三态收敛）                 │
└───────────────────────────┬────────────────────────────┘
                            │ 读状态流 / 调 apiClient
┌───────────────────────────▼────────────────────────────┐
│ 领域/状态层                                              │
│  DshRepository（进程级单例状态源）                        │
│  NotificationStateMachine（纯 Kotlin 通知状态机）        │
└─────────────┬──────────────────────────┬────────────────┘
              │ 服务写、UI 读             │ 产出通知指令
┌─────────────▼──────────────┐  ┌────────▼────────────────┐
│ 服务层                      │  │ 数据/网络层              │
│  EventStreamService(前台)   │  │  DshApiClient(OkHttp)   │
│  SessionEventDeduper(去重)  │  │  SettingsStore(DataStore)│
│  NotificationHelper         │  │  model/*(协议模型)       │
│  ApprovalReceiver(广播)     │  │  FrameParser(帧解析)     │
└────────────────────────────┘  └─────────────────────────┘
```

依赖规则：**上层只依赖下层**；`EventStreamService` 是唯一「写状态」的服务端，
UI 只通过 `DshRepository` 的只读流消费状态，实现服务与界面生命周期彻底解耦。

## 4. 模块划分与职责

| 模块（包路径） | 职责 |
|----------------|------|
| `ui` | Compose 界面 + ViewModel + UI 状态与策略 |
| `service` | 前台监听服务、通知状态机、事件去重 |
| `notify` | 系统通知构建与广播接收器（通知内按钮应答） |
| `network` | DshApiClient：HTTP RPC、现代交互回答及工作区订阅连接 |
| `data` | SettingsStore：服务器地址 + 通知开关持久化 |
| `model` | 协议模型、帧解析、信封结构（对照宿主 schema） |
| 根包 | DshApplication（初始化）、DshRepository（状态源）、MainActivity |

### 4.1 DshRepository（单例状态源）

进程级单例，是**服务与 UI 之间的唯一数据总线**：

- 持有全局唯一的 `apiClient` 与 `settingsStore`；
- 会话快照 / 工作区 / 连接状态 / 排队消息 / 审批 / 提问 / 统计等，全部以
  `StateFlow`（快照型）或 `SharedFlow`（事件型）暴露；
- **服务侧写入、UI 只读**：`publish*` 方法仅供 `EventStreamService` 调用；
- 事件型通道（`sessionEvents`、`localCancelEvents`、`streamKickRequests` 等）用
  `SharedFlow` 带缓冲容量兜底瞬时高峰。

### 4.2 DshApiClient（通信层）

宿主通信的**唯一入口**，当前运行路径使用两类通道：

- **HTTP 请求**：`POST /api/<method>`，JSON-RPC 风格信封
  `{type:"client-request", rpcId, method, payload}`，响应折叠为三分支
  `Ok / BizError / NetError`；`session.list` 和 `session.history` 由调用方按周期查询。
- **工作区订阅**：新版宿主通过 `/api/remote.mux` 提供 `workspace/follow` 流，当前只用它接收工作区与归档集合更新。旧版会话事件 WebSocket 工厂仍在源码中，但新版运行路径不启动它们。

关键健壮性设计：
- 换址用「世代计数」：更新地址即自增世代并**关闭存量工作区流**，防止旧流与新调用分裂到两台服务器；
- 工作区订阅流支持心跳、断线退避重连和网络恢复后立即重连；
- 应答走短超时专用客户端（预算 <10s 广播 ANR 窗口）；
- 模拟器调试 Host 改写拦截器仅存在于 debug 构建（`BuildConfig.DEBUG`）。

### 4.3 EventStreamService（前台服务）

后台同步服务，保证退后台/息屏仍周期对账会话状态，并维持工作区订阅：

- `specialUse` 前台服务类型（规避 Android 15 dataSync 的 6h 累积限制），
  配套 `onTimeout` 优雅降级兜底；
- 每 3 秒调用一次 `session.list` 并运行 **baseline 对账**，失败后按退避间隔重试；
- 新版宿主通过一个独立工作区流更新工作区与归档集合，该流断开后退避重连；
- `ConnectivityManager` 监听网络切换立即重连；
- 旧版会话事件 WebSocket 不在当前启动路径中，当前对话内容由会话页调用历史接口读取。

### 4.4 NotificationStateMachine（纯状态机）

M3 核心，**纯 Kotlin、无 Android 依赖、时钟可注入**，可 JVM 全分支确定性单测：

- 消费 baseline + host/mux 帧流 + 本地取消事件；
- 产出**通知指令流**（`Show/Cancel` × 回合完成/审批/提问），由服务执行真正的系统通知；
- 规则覆盖：回合完成仅 `prev==running:true → running:false` 触发、本地取消 3s 抑制窗、
  subagent 会话不通知、审批/提问按 id 键控去重等。

### 4.5 ConversationViewModel（对话页）

对话页当前消息与状态来自以下路径：

1. 打开时 `sessionHistory()` 拉历史（user/assistant/tool）；
2. 会话运行期间每 2 秒调用一次历史接口，对账新消息；
3. repository 的 `session.list` 快照驱动 `running` 状态。

流式聚合与节流代码仍保留，供实时事件入口复用；新版宿主关闭会话事件流时，对话页主要靠两秒一次的历史对账更新。

## 5. 核心数据流

### 5.1 会话列表与通知

```
session.list（每 3 秒） ──> EventStreamService ──> NotificationStateMachine
        │                                                │ 产出通知指令
        │ 写快照                                          ▼
        └────────────> DshRepository.sessions      NotificationHelper(系统通知)
                          │ 只读
                          ▼
                    SessionListScreen（三态分组）

remote.mux workspace/follow ──> EventStreamService ──> DshRepository.workspaces
```

### 5.2 对话页实时渲染

```
进入会话时 sessionHistory() ──> ConversationViewModel._messages
会话运行期间每 2 秒 sessionHistory() ──> 按服务端 key 与近期本地发送项合并
（保留已到达的当前项；历史回显确认本地发送后原位替换，流式占位不从历史重放）
```

### 5.3 用户操作

```
发消息/停止/插话/审批 ──> ViewModel ──> DshApiClient ──> POST /api/<method>
                                                     └──> POST /api/respond（审批/提问）
宿主状态变化 ──> session.list 周期对账 ──> 状态机 + 状态源 ──> UI 收敛（以宿主快照为准）
```

关键原则：**UI 以宿主回显为最终事实源**，本地只做轻量乐观更新（用户消息、插话禁用标记），
失败时回滚并提示，避免「静默失败」。

## 6. 关键设计决策

1. **单例状态源解耦**：服务与 UI 不直接通信，全部经 `DshRepository` 的 Flow 中转，
   解决后台服务与前台界面的生命周期耦合。
2. **状态机纯化**：通知判定逻辑与 Android 分离，时钟可注入 → 全分支确定性单测。
3. **无本地数据库**：会话快照为易失内存态，重启后由 `session.list` baseline 重建；
   仅 DataStore 存服务器地址 + 三个通知开关，大幅降低复杂度与一致性风险。
4. **同步方式**：会话状态与历史内容通过 HTTP 周期对账更新；工作区通过 `remote.mux` 的 `workspace/follow` 流更新。换址时关闭工作区流，避免旧流与新请求分裂到两台服务器。
5. **容错优先**：退避重连、seq 去重、id 键控去重、三态回执收敛、错误码可见化，
   全程无「静默失败」路径。
6. **私密配置外置**：服务器地址/受信权威经 `BuildConfig` 注入（`personal.properties`，
   git 忽略），源码不含私人域名，可安全开源。

## 7. 非功能特性

| 维度 | 措施 |
|------|------|
| 可靠性 | 周期 baseline 对账、工作区流退避重连、网络切换即时恢复 |
| 性能 | 流式节流合批、大列表解码走 IO 线程、独立 WS 调度器 |
| 安全性 | TLS 系统信任链零配置、私密域名外置、release R8 混淆 |
| 可测试性 | 纯状态机可 JVM 单测、MockWebServer 网络单测、纯函数抽离 |
| 可维护性 | 单一职责分包、协议对照 schema 源码钉死、注释标注设计溯源 |

## 8. 关键文件索引

| 文件 | 作用 |
|------|------|
| `app/src/main/java/dev/dshmobile/DshRepository.kt` | 单例状态源 |
| `app/src/main/java/dev/dshmobile/network/DshApiClient.kt` | 通信客户端 |
| `app/src/main/java/dev/dshmobile/service/EventStreamService.kt` | 前台监听服务 |
| `app/src/main/java/dev/dshmobile/service/NotificationStateMachine.kt` | 通知与交互状态机 |
| `app/src/main/java/dev/dshmobile/service/DshInteractionStream.kt` | 现代交互流与等待声明 |
| `app/src/main/java/dev/dshmobile/service/DshThinkingStream.kt` | 当前会话实时思考预览连接 |
| `app/src/main/java/dev/dshmobile/model/ThinkingPreviewState.kt` | 思考快照与增量的纯状态归约 |
| `app/src/main/java/dev/dshmobile/model/InteractionTarget.kt` | 活动交互与稍后回答目标 |
| `app/src/main/java/dev/dshmobile/ui/ConversationViewModel.kt` | 对话页 ViewModel |
| `app/src/main/java/dev/dshmobile/data/SettingsStore.kt` | 配置持久化 |
| `app/src/main/java/dev/dshmobile/DshApplication.kt` | 入口与通知通道初始化 |
| `app/src/test/java/dev/dshmobile/` | 单元测试（状态机/解析/去重/流式） |

## 2026-10-20 现代交互通道

会话列表与历史继续周期轮询，工作区继续订阅 `workspace/follow`。
审批和提问由 `DshInteractionStream` 订阅 `/api/remote.mux` 上的 `$events`，
通过 `$events/result` 回答；限时提问通过 `userQuestions/attachWait` 取得剩余等待时间。
超时后的问题由 `userQuestions` 与 `inbox` 投影恢复，通过 `userQuestions/answer` 回答。

状态机统一管理卡片、通知、断线重放和服务器世代。页面保存按题草稿并禁止重复提交。
提交方不保证收到宿主取消事件，因此成功回执会通知服务按完整地址收敛本地状态。
普通消息仍使用原有队列；等待交互时 App 显示原因，不自动替用户选择或重发消息。

实现和验收入口见 [修复方案](requirements/2026-10-20-交互缺失与消息排队修复方案.md)。

## 2026-10-21 底部实时思考预览

当前页面的 ViewModel 持有 DshThinkingStream，在 `/api/remote.mux` 订阅
`session/follow`，显式请求 `assistantStream=true`。ThinkingPreviewState
恢复活动尝试的紧凑快照，再顺序消费实时片段；每 80ms 合并发布底部思考预览。
该数据不进入 sessionEvents 或消息历史，避免重复合并与统计。

底部复用 ThinkingStreamRow 显示最新末行、贴尾滚动与扫光，并可点开全文。
正文或工具块开始、尝试结束、断连或换地址均清空预览；页面销毁关闭本订阅。
历史消息仍走既有对账，上滑仍关闭自动跟随。有效快照和生成结束调度原有请求闸门补读，
覆盖长思考时未观察到 running=true 或断线错过结束的情况；不修改整个回合的运行状态。

方案及验收入口：[底部实时思考单行预览](requirements/2026-10-21-底部实时思考单行预览.md)。
