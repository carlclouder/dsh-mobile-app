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
| 网络 | OkHttp（HTTP 单次调用 + 周期轮询；WebSocket 代码保留但新版宿主已无事件流端点） |
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
| `network` | DshApiClient：HTTP RPC + 双 WebSocket 工厂 |
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

宿主通信的**唯一入口**，两类通道：

- **单次调用**：`POST /api/<method>`，JSON-RPC 风格信封
  `{type:"client-request", rpcId, method, payload}`，响应折叠为三分支
  `Ok / BizError / NetError`；
- **事件流**：两条只下行 WebSocket —— `events.mux`（会话事件：对话块/审批/提问/队列）
  与 `events.host`（宿主状态：会话增删、running 翻转）。

关键健壮性设计：
- 换址用「世代计数」：更新地址即自增世代并**关闭全部存量 WS**，防止旧流与新调用分裂到两台服务器；
- 独立 WS 客户端（`pingInterval 30s` + `readTimeout 90s` 防 NAT 半开）；
- 应答走短超时专用客户端（预算 <10s 广播 ANR 窗口）；
- 模拟器调试 Host 改写拦截器仅存在于 debug 构建（`BuildConfig.DEBUG`）。

### 4.3 EventStreamService（前台服务）

应用的心脏，保证退后台/息屏仍持续收事件：

- `specialUse` 前台服务类型（规避 Android 15 dataSync 的 6h 累积限制），
  配套 `onTimeout` 优雅降级兜底；
- 每条流一个协程：开 socket → 挂起等关闭 → **退避重连（1s→60s）**；
- 冷启动/重连用 `session.list` **baseline 对账**；
- `ConnectivityManager` 监听网络切换立即重连；
- `SessionEventDeduper` 按 seq 水位去重，防止重连 replay 造成「流式叠词」。

### 4.4 NotificationStateMachine（纯状态机）

M3 核心，**纯 Kotlin、无 Android 依赖、时钟可注入**，可 JVM 全分支确定性单测：

- 消费 baseline + host/mux 帧流 + 本地取消事件；
- 产出**通知指令流**（`Show/Cancel` × 回合完成/审批/提问），由服务执行真正的系统通知；
- 规则覆盖：回合完成仅 `prev==running:true → running:false` 触发、本地取消 3s 抑制窗、
  subagent 会话不通知、审批/提问按 id 键控去重等。

### 4.5 ConversationViewModel（对话页）

对话页数据**三来源合并**：

1. 打开时 `sessionHistory()` 拉历史（user/assistant/tool）；
2. mux 流 `session/event` 帧实时追加；
3. repository 会话快照驱动 `running` 状态。

流式渲染做了**节流合批**：高频 `assistant/chunk` 增量先追加到内存 `StringBuilder`，
再由协程每 80ms 合并刷新一次消息列表，把「每 chunk 刷全列表」锁死为常数频率，
消除 P0 卡死。

## 5. 核心数据流

### 5.1 会话列表与通知

```
events.host / session.list ──> EventStreamService ──> NotificationStateMachine
        │                                                │ 产出通知指令
        │ 写快照                                          ▼
        └────────────> DshRepository.sessions      NotificationHelper(系统通知)
                          │ 只读
                          ▼
                    SessionListScreen（三态分组）
```

### 5.2 对话页实时渲染

```
sessionHistory() ──> ConversationViewModel._messages（历史，按 key 合并去重）
events.mux session/event 帧 ──> DshRepository.sessionEvents ──> ViewModel 实时 upsert
        （chunk 增量 → 节流合批 → __streaming__ 占位 → settled 消息替换）
```

### 5.3 用户操作

```
发消息/停止/插话/审批 ──> ViewModel ──> DshApiClient ──> POST /api/<method>
                                                     └──> POST /api/respond（审批/提问）
宿主状态变化 ──> 双 WS 推送 ──> 状态机 + 状态源 ──> UI 收敛（以宿主回显为准，避免乐观闪烁）
```

关键原则：**UI 以宿主回显为最终事实源**，本地只做轻量乐观更新（用户消息、插话禁用标记），
失败时回滚并提示，避免「静默失败」。

## 6. 关键设计决策

1. **单例状态源解耦**：服务与 UI 不直接通信，全部经 `DshRepository` 的 Flow 中转，
   解决后台服务与前台界面的生命周期耦合。
2. **状态机纯化**：通知判定逻辑与 Android 分离，时钟可注入 → 全分支确定性单测。
3. **无本地数据库**：会话快照为易失内存态，重启后由 `session.list` baseline 重建；
   仅 DataStore 存服务器地址 + 三个通知开关，大幅降低复杂度与一致性风险。
4. **双通道分工**：单次调用走 HTTP RPC，持续推送走两条 WS，避免轮询；
   换址用世代计数统一收拢两条流，杜绝「事件与调用分裂」。
5. **容错优先**：退避重连、seq 去重、id 键控去重、三态回执收敛、错误码可见化，
   全程无「静默失败」路径。
6. **私密配置外置**：服务器地址/受信权威经 `BuildConfig` 注入（`personal.properties`，
   git 忽略），源码不含私人域名，可安全开源。

## 7. 非功能特性

| 维度 | 措施 |
|------|------|
| 可靠性 | 退避重连、baseline 对账、去重、网络切换即时重连 |
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
| `app/src/main/java/dev/dshmobile/service/NotificationStateMachine.kt` | 通知状态机 |
| `app/src/main/java/dev/dshmobile/ui/ConversationViewModel.kt` | 对话页 ViewModel |
| `app/src/main/java/dev/dshmobile/data/SettingsStore.kt` | 配置持久化 |
| `app/src/main/java/dev/dshmobile/DshApplication.kt` | 入口与通知通道初始化 |
| `app/src/test/java/dev/dshmobile/` | 单元测试（状态机/解析/去重/流式） |
