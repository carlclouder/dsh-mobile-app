# DSH Mobile 当前架构

> 对齐日期：2026-11-24。本文描述当前源码和运行路径。[DESIGN.md](DESIGN.md) 保留早期设计及迁移过程，不能作为当前部署步骤。功能操作见 [USAGE](../USAGE.md)，验收记录见[实时思考](../test-evidence/thinking-preview-20261021/README.md)及[现代交互](../test-evidence/pending-interactions-20261020/README.md)。

## 1. 系统边界

DSH Mobile 是 Kotlin 安卓客户端，界面由 Jetpack Compose 与 Material3 构建。模型、会话和工作区由电脑端 DeepSeek Harness 管理，App 通过 HTTP 与 WebSocket 访问宿主。

```text
Android App
  → Tailscale HTTPS / 已配置的宿主地址
  → Tailscale Serve 的明确目标
  → DSH 本体，或经过已配置的认证网关再到 DSH
```

生产沿用已有部署，DSH 通常使用 `127.0.0.1:3080`；`3081` 是隔离测试端口。网关源码的默认端口不构成生产推荐值，Serve 不能在两端口之间随意切换。部署和认证边界见[远程访问指南](../Tailscale远程访问DSH-安装使用文档.md)与[网关说明](dsh-auth-gateway-plugin.md)。

App 不提供模型服务配置、账户配对或文件上传管理。语音输入调用设备识别服务，只回填输入框。

## 2. 分层与职责

| 层与模块 | 职责 |
| --- | --- |
| Compose 页面 | 列表、设置、对话、交互卡、队列、折叠内容和滚动行为 |
| Compose 外观层 | 冰蓝光幕（默认）与极光舱的配色、材质和短动效；皮肤不控制业务状态、导航或宿主接口 |
| `ConversationViewModel` | 当前会话的历史合并、发送、队列、交互草稿、统计及思考连接生命周期 |
| `DshRepository` | 进程级客户端、设置与状态流，连接前台服务和页面 |
| `EventStreamService` | 前台服务，周期对账列表，维持工作区及现代交互连接，执行通知指令 |
| `NotificationStateMachine` | 纯 Kotlin 状态机，统一会话、待处理交互、通知及回执收敛 |
| `DshInteractionStream` | 现代交互订阅、活动客户端身份、限时等待声明、重连与失效保护 |
| `DshThinkingStream` | 当前页面独立会话流，恢复思考快照、接收增量并调度补读历史 |
| `ThinkingPreviewState` | 校验修订号与原始片段序号，归约活动尝试的思考文本 |
| `DshApiClient` / `DshAuthSession` | 请求封装、协议兼容、地址世代、套接字登记及 Cookie 认证 |
| `SettingsStore` | 服务器地址、通知开关和外观偏好持久化 |

服务与页面通过 `StateFlow` 和 `SharedFlow` 交换状态及事件，避免直接持有对方界面对象。通知判断与网络传输分开；思考预览与已落定消息分开，防止预览被重复并入历史和统计。

`NotificationStateMachine.sessionSnapshot()`复制Map内的每个`SessionState`再发布。只复制Map会共享内部可变值，使新旧快照相等、状态流漏发运行结束；当前实现隔离发布值，保留归档过滤和顺序，通知判定仍基于宿主真实运行状态。对应实际发送、完成清理与重进证据见[顺序修复验收](../test-evidence/message-order-20261021/README.md)。

## 3. 通信方式

当前运行路径同时使用周期 HTTP 对账和 `/api/remote.mux` WebSocket。旧 `/api/events.host`、`/api/events.mux` 工厂与兼容解析仍保留在源码中，但不作为当前会话推送入口。

### HTTP 请求

`DshApiClient.call()` 优先使用现代端点和 `payload.args` 参数包装；列表使用 `args._request`，历史及常规操作使用各自的 `args.request`。收到端点不存在时，尝试旧点号方法及旧参数格式。历史映射为 `session/page`，并读取有效游标作为 `throughSeq`，不能无限制向未来查询。

现代原始接口使用 `callModernRaw()`，保留 `void`、`null`、布尔值等响应，不强制要求对象。请求结果区分成功、业务错误与网络错误。

| 数据 | 当前路径 |
| --- | --- |
| 会话列表、运行状态 | 前台服务约每 3 秒调用 `session.list`，再由状态机对账 |
| 已落定消息、工具、统计 | 打开会话时读取历史；运行中约每 2 秒对账 |
| 发送、停止、队列、重命名、分叉、归档 | 页面调用对应 HTTP 方法，按宿主响应与后续状态收敛 |
| 模型目录 | 新版宿主优先使用 `session/modelCatalog`，保留旧接口兼容 |

模型与思考等级以宿主目录为准；缺失目录时不把界面可打开视为宿主切换成功。

### 工作区订阅

`EventStreamService` 通过 `remote.mux` 的 `workspace/follow` 接收工作区与归档集合。该连接独立退避重连，归档集合用于列表和通知过滤。

### 现代交互

```text
remote.mux 的 $events
  → DshInteractionStream
  → NotificationStateMachine
  → Repository 中的审批/提问状态
  → 页面交互卡 + 系统通知

活动交互回答 → $events/result
限时问题声明 → userQuestions/attachWait
稍后回答状态 → userQuestions 与 inbox 投影
稍后回答提交 → userQuestions/answer
```

活动交互与稍后回答使用明确的 `InteractionTarget`，不能混用回答地址。等待声明记录剩余时间，成功回答后释放本地等待；重连恢复未答交互。投影、回执和活动客户端共同防止旧卡片重复提交。旧宿主回答仍保留 `/api/respond` 兼容路径。

页面保存按题草稿，区分提交中、同步中及已排队。回答成功后显式通知服务收敛本地卡片，不能依赖提交方一定收到宿主取消广播。普通消息队列与交互答案分开；等待交互时显示原因，不自动替用户选择。

详细方案见[交互缺失与消息排队修复](requirements/2026-10-20-交互缺失与消息排队修复方案.md)。

### 当前会话实时思考

```text
remote.mux 的 session/follow（assistantStream=true）
  → ThinkingPreviewState（快照 + 连续增量）
  → 每 80ms 合并发布思考预览
  → 当前页面底部单行末行 / 展开全文

有效快照、有效生成结束
  → ViewModel 原有历史请求闸门
  → 合并待补查、串行读取历史
  → 更新已落定回复
```

快照恢复活动尝试及紧凑段；紧凑段按成员数量计数，增量仍校验原始片段序号与修订号。序列中断或格式异常时清空连接状态，重新获取快照，避免把旧文本接到新生成上。

正文或工具块开始、尝试结束、断线、换址时清空活动预览，页面销毁只关闭自己的思考订阅。生成结束不是整个回合结束，不修改 `running`。

有效快照及生成结束均调度原有历史补查，覆盖列表请求超时而未观察到 `running=true`、或重连错过结束的情况。回调只调度协程，不在套接字锁内执行网络请求。

思考预览不进入 `sessionEvents` 或消息历史。正文仍主要来自历史对账，不能据此宣称整条回复逐字流式显示。详细设计见[底部实时思考单行预览](requirements/2026-10-21-底部实时思考单行预览.md)。

## 4. 状态、滚动与通知

会话列表按运行状态与活动时间分组，组内按标题排序。空白和子代理会话隐藏；本机成功发送消息后记录本地事实，使该会话立即豁免空白隐藏。尚未确认的本地发送项继续保留。

发送与历史回显通过同一个 UUID 关联：

```text
ViewModel 发送前生成 UUID
  → 本地 UiMessage.promptRequestId 与 local-user-<UUID> 键
  → sessionPrompt(..., requestId) / session/prompt.requestId
  → 宿主 user/message.source.rpcId
  → ConversationEvent.UserMessage.promptRequestId
  → UiMessage.promptRequestId
  → 历史合并或实时更新确认本地发送并清除本地副本
```

对于未被明确拒绝的本地消息，同编号确认不依赖正文和时间；消息按宿主历史顺序定位，使提问位于对应回复之前。若当前列表已存在服务端键，同时又残留同编号本地项，更新仍清除本地副本，不能只更新服务端项后返回。

两条非空请求编号不同的消息禁止退回文字匹配，避免重复发送“继续”等短句时误合并。只有至少一端缺少编号时，才保留旧的同文顺序匹配，要求双方有时间、服务端不早于本地且相差不超过 120 秒；明确拒绝的本地项不参与匹配。因此本次修复不能视为旧宿主时钟偏差已解决，也不使用跨设备时间对全列表排序。

历史请求通过 `HistoryRequestGate` 串行执行。请求进行中再次触发时合并一次待补查，避免并发旧快照回写。历史重放不重复添加流式占位内容。

对话列表接近底部时跟随更新；拖动查看历史后停止跟随，跳底或发送消息恢复。思考区域可展开，长行贴尾，快速预览不会强制抢回用户正在查看的旧历史。

连续多个既无正文、也无可见工具结果的思考步骤默认折叠成一行（行首 `▸` 折叠标识，摘要「▸ 💭 思考过程 · N 步 · M 次工具调用 · 点按展开」）；手动点开才还原成原来一个个独立气泡（各自的时间行和自己的「思考过程 · 点按展开」），列表刷新不会自动展开。列表末尾那一组收起时改为「▸ 💭 N 步」+ 只滚动显示最新一条思考内容，其后出现带正文的回答就退回普通摘要行。带正文或带可见工具结果的步骤仍是独立气泡，不并入组。分组只发生在渲染层，不改变消息产生与合并；跳底和贴底按渲染项数计算。口径见 [连续思考步骤默认折叠](requirements/2026-10-22-连续思考折叠.md)。

底部统计、任务、交互和队列区域限制最大高度并可滚动，避免多题多选项挤掉消息列表和输入框。

通知状态机消费宿主快照、现代交互与本地事件，产出显示或撤销指令。回合完成依赖观测到运行状态由真变假；未观测到的过渡不能保证触发通知。子代理和归档会话过滤、交互键控去重及本地取消抑制用于减少重复或误报。实际送达仍依赖 Android 权限、后台策略和网络。

## 5. 认证与资源生命周期

`DshAuthSession` 从输入地址提取令牌，换取并保存 Cookie；令牌消费后不能无限重试。凭证与访问地址有关，换址不能假设旧凭证通用。

`DshApiClient` 使用地址世代标记并关闭登记的旧套接字。工作区、交互和思考回调核对当前世代，避免旧服务器的结果污染新地址；交互回答还核对当前活动客户端。思考订阅由页面关闭，前台服务连接不随页面收起而一并关闭。

后台使用前台服务维持同步，网络变化触发恢复。订阅分别采用退避重连，不能将某一条流连接成功视为全部通道正常。

私人默认地址放在未入库的 `app/personal.properties`。发布签名读取 `keystore/keystore.properties`；缺失时不自动使用 Debug 签名，具体构建步骤见 [README](../README.md#本地构建)。

## 6. 渲染与技术栈

界面采用 Compose；Markdown 使用 `compose-markdown 0.7.2`，其 Android TextView 渲染支持表格、图片、链接、代码和列表。正文关闭文本选择以允许滚动，思考等 Compose 文本可选中。因此不能统一承诺所有气泡长按复制。Mermaid 不渲染为图形。

网络使用 OkHttp 4.12.0，JSON 使用 kotlinx-serialization，异步状态使用协程与 Flow。设置持久化使用 DataStore，认证 Cookie 使用 SharedPreferences；会话历史不建立本地数据库，以宿主为权威源。

## 7. 关键源码入口

| 文件 | 作用 |
| --- | --- |
| [DshRepository.kt](../app/src/main/java/dev/dshmobile/DshRepository.kt) | 进程级状态与客户端 |
| [DshApiClient.kt](../app/src/main/java/dev/dshmobile/network/DshApiClient.kt) | 请求、协议兼容与套接字登记 |
| [DshAuthSession.kt](../app/src/main/java/dev/dshmobile/network/DshAuthSession.kt) | 令牌交换和 Cookie |
| [EventStreamService.kt](../app/src/main/java/dev/dshmobile/service/EventStreamService.kt) | 周期同步与前台服务 |
| [NotificationStateMachine.kt](../app/src/main/java/dev/dshmobile/service/NotificationStateMachine.kt) | 通知与交互状态 |
| [DshInteractionStream.kt](../app/src/main/java/dev/dshmobile/service/DshInteractionStream.kt) | 现代交互、等待和恢复 |
| [DshThinkingStream.kt](../app/src/main/java/dev/dshmobile/service/DshThinkingStream.kt) | 当前会话思考连接 |
| [ThinkingPreviewState.kt](../app/src/main/java/dev/dshmobile/model/ThinkingPreviewState.kt) | 思考流纯状态归约 |
| [InteractionTarget.kt](../app/src/main/java/dev/dshmobile/model/InteractionTarget.kt) | 回答目标类型 |
| [ConversationViewModel.kt](../app/src/main/java/dev/dshmobile/ui/ConversationViewModel.kt) | 历史合并与操作 |
| [ConversationActivity.kt](../app/src/main/java/dev/dshmobile/ui/ConversationActivity.kt) | 对话渲染与滚动 |
| [ThinkingProcessGrouping.kt](../app/src/main/java/dev/dshmobile/ui/ThinkingProcessGrouping.kt) | 连续思考步骤折叠分组（纯 Kotlin） |
| [SessionListScreen.kt](../app/src/main/java/dev/dshmobile/ui/SessionListScreen.kt) | 分组、过滤与列表操作 |
| [SettingsStore.kt](../app/src/main/java/dev/dshmobile/data/SettingsStore.kt) | 设置持久化 |
| [单元测试目录](../app/src/test/java/dev/dshmobile/) | 真实 Kotlin 函数与网络路径测试 |
