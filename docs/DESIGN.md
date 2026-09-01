# DSH Mobile —— 安卓端 DeepSeek Harness 远程客户端 设计文档

版本：v1.1（对抗评审修正版）
日期：2026-08-24
状态：已按对抗评审仲裁清单修正，待复审确认

> 修订说明：v1.0 经双模型对抗评审（对抗质疑 + 独立佐证 + 仲裁），发现 1×P0、4×P1、
> 12×P2 问题，本版逐项落实修正。修正溯源见 §12 评审修正对照表。

---

## 1. 背景与目标

PC（<PC-name>）上运行 DeepSeek Harness Web GUI（`dsh web`，监听 127.0.0.1:3080）。
手机（<phone-model>，Android）已通过 Tailscale 与 PC 组网，经
`https://<PC-name>.tailnet.ts.net`（tailscale serve 隧道）可访问该 GUI。

现状痛点：手机浏览器直接打开 PC 网页，排版拥挤、无后台通知、多会话切换困难。

目标：开发一个原生安卓 APP，满足三大硬需求：

1. **会话完成通知**：agent 回合结束等待输入时弹系统通知，APP 退后台/息屏也能收到。
2. **多会话快速处理**：首页原生会话列表，按"跑动中 / 等你输入 / 空闲"分组，一眼看清全局。
3. **移动端排版**：界面按手机屏设计，不再拥挤。

## 2. 已确认需求（用户拍板版）

| # | 需求 | 结论 |
|---|------|------|
| 2.1 | 连接走 Tailscale serve 隧道，地址 `https://<PC-name>.tailnet.ts.net`，APP 内可改 | ✅ |
| 2.2 | 回合完成通知（前台服务保活，息屏可达） | ✅ |
| 2.3 | 多会话列表（全部项目、三态分组、快速进入） | ✅ |
| 2.4 | 完整交互：看对话、发消息、停轮次、答审批/提问 | ✅ |
| 2.5 | 通知带审批按钮（"允许一次 / 拒绝"，不打开 APP 直接批） | ✅ 用户确认要做 |
| 2.6 | 单机使用（仅 <phone-model>，不做配对机制） | ✅ 用户确认 |
| 2.7 | 界面中文 | ✅ 用户确认 |
| 2.8 | v1 不做：模型配置页、文件/图片上传、语音、服务端改动 | ✅ |

### 2.9 新增需求（2026-08-25 用户提出，需同步实施）

| # | 需求 | 说明 |
|---|------|------|
| 2.9.1 | **工作区功能** | App 需反映 Web UI 的工作区结构（按工作区分组/过滤会话）。数据：workspace.list`{title,path,sessionIds,archivedSessionIds}` + host/workspace-changed 帧 |
| 2.9.2 | **合理布局/可伸缩** | 会话列表的工作区过滤条等 UI 需可折叠/伸缩，避免遮挡或干扰会话内容 |
| 2.9.3 | **隐藏已归档会话** | 大量归档会话出现在列表：archive 的会话**不显示、不弹通知**。数据：workspace.list.archivedSessionIds + host/archived-sessions-changed 帧；实测共 46 个归档（约 94 会话中） |
| 2.9.4 | **语音识别修复** | 语音输入功能异常，需测试并修复（模拟器无 recognitionService 是真机/模拟器差异，需确认真机可用与降级路径正确） |

## 3. 关键技术事实（源码调研钉死，非猜测）

以下结论全部来自 `@deepseek-ai/dsh@0.1.0-rc.8` 安装包源码阅读与实机 API 调用：

### 3.1 通信协议（dsh-host-apiproxy / dsh-client-connection）

- **单次调用**：`POST /api/<method>`，请求头必须 `Content-Type: application/json`
  （否则 415 拒绝），请求体
  `{"type":"client-request","rpcId":"<任意串>","method":"<method>","payload":{...}}`，
  响应体 `{"type":"server-response","rpcId":..., "result":{"ok":true,"value":...}}` 或
  `{"ok":false,"error":{"code":...,"message":...,"details":{}}}`。
- **事件流**：两条**只下行** WebSocket：
  - `wss://…/api/events.mux` —— 会话事件（对话块、审批、提问、队列）
  - `wss://…/api/events.host` —— 宿主事件（会话增删、running 状态翻转）
  每条 WS 文本消息为**信封** `{"type":"server-request","rpcId":...,"method":...,"payload":<帧>}`；
  **rpcId 位于信封层而非帧 payload 内**（源码 client.js L10149–10162：先 parse 信封再取
  full.rpcId 与 full.payload）。审批/提问应答必须回显**信封层 rpcId**。
- **信任栏栅**：非浏览器客户端只要 Host 头是受信权威（`<PC-name>.<tailnet>.ts.net` 已在
  dsh 启动参数 `--trusted-host` 中）即放行；APP 原生请求（OkHttp，无 Origin 头）天然满足。
- **审批/提问应答**：`POST /api/respond`，请求体
  `{"type":"client-response","rpcId":"<信封层rpcId>","result":{"ok":true,"value":{...}}}`。
  审批 value：`{"sessionId","approvalId","outcome":"allowed-once"|"rejected"}`；
  提问 value：`{"sessionId","answer":{"answers":[{"id","selected":[...],"custom"?}]}}`。
- **回执语义**：`{"accepted":true}` 或 `{"accepted":false,"reason":"not-pending"|"bad-response"}`
  （rpc.schema.js L110–113）。`not-pending` 表示该审批/提问**已在别处被处理**，
  正确行为是撤通知并提示"已处理"，**不是重试**。

### 3.2 通知判定信号与状态机（评审修正后）

| 信号 | 帧 | 判定与修正后规则 |
|------|----|------|
| 回合完成等输入 | host 流 `host/session-status {sessionId, running:false}` | 该会话内存态 prev==running:true 时触发通知。**抑制规则（P1 修正）**：仅 `prev==null`（冷启动/服务重启，内存无快照）时 baseline 静默；**WS 断连重连**场景下内存快照 prevRunning=true 且 baseline running=false 的会话**补发通知**（host 流无状态回放，源码 api-proxy.js L3176–3205，一刀切抑制会永久漏报断连窗口内的完成事件） |
| 需要审批 | mux 流 `approval/requested {sessionId, approvalId, toolName, reason?}` | 到达即通知（高优先级）。**去重（P2）**：mux 重连会原样 replay 仍待处理的帧且 rpcId 复用（events.d.ts L47–49），故通知按 `approvalId` 键控——同一 approvalId 已有活动通知则更新不重响 |
| agent 提问 | mux 流 `question/requested {sessionId, questions:[...]}` | 到达即通知，按信封 rpcId 键控去重（同上 replay 语义） |
| 取消/错误终止 | 同样表现为 running:true→false 翻转，帧上无 cause 字段 | **误报对策（P2）**：本地发起 `session.cancel` 后对该 sessionId 设 3 秒抑制窗，窗内到达的 running:false 不触发"回合完成"通知 |
| subagent 会话 | `host/session-added` 可携带 `origin:'subagent'`（events.schema.js L66） | **策略（P1）**：origin=subagent 的会话**不触发任何通知**、列表默认隐藏（子代理每轮结束都会翻转 running，不过滤会造成通知轰炸）。实施首日实测验证 subagent 是否出现在 host 流，若出现行为不同再校准 |
| blank 会话 | `session.list` 的 `blank:true`（无 turn/start） | **列表策略（P2）**：默认隐藏（对齐上游"clients hide blank sessions and reuse them"惯例，sessions.d.ts L189–191），避免空白会话堆积 |
| 会话列表基线 | `POST /api/session.list` → `items[{sessionId,updatedAt,running,blank,origin?,cwd,projections}]` | 前台服务启动/重连时恢复各会话状态基线 |
| 流错误 | 两流均可推 `stream/error` 帧（events.schema.js L57/L82） | **帧分发表补充（P2）**：记日志 + 触发该流重连 |
| 标题 | `session.list` 的 `projections.values.title`（以实测定型，单测锁死） | 列表显示名 |

### 3.3 时间语义校准（P2 修正）

- `SessionSummary.updatedAt` = **创建时间与最近人类提交 prompt 时间的较晚者**
  （sessions.d.ts L176–180），**不是**最近活动时间。列表相对时间文案用"上次提问"，
  "等你输入"分组条件为"非 blank 且 7 天内有提问"。
- `SessionSummary` 无 startedAt 字段、`host/session-status` 帧无时间戳 →
  "跑动中"显示的运行时长用**本地记录的 running 翻转为 true 的时刻**（即
  localRunningSinceMillis 赋值时刻，见 §5.2）近似计算，
  UI 标注"约"。进程重启后该近似值归零重计。

### 3.4 环境事实

- DSH 显式拒绝 `--host 0.0.0.0`（startup.js L40，安全设计：远程访问无认证层前不开放）。
  精确表述：仅 0.0.0.0 全接口绑定被禁；但指定具体 IP 绑定仍无 TLS 且需改服务端启动参数，
  违反 v1"不改服务端"约束，且 serve 隧道已实测可用（HTTP 200）——**serve 隧道是既定入口**。
- `*.ts.net` 证书由 **Let's Encrypt 公共 CA 签发**（Tailscale 官方文档），Android
  WebView/OkHttp 走系统信任链**零配置受信**，无需安装任何根证书（v1.0 的"tailnet CA"
  表述有误，已修正）。
- 本机当前无 JDK17 / Android SDK / Gradle（仅 JRE 8），需自动安装构建链。
- 手机 <phone-model> 已装 Tailscale 且与 PC 直连活跃。

### 3.5 Android 平台约束（评审新增）

- **Android 13+（API 33）**：`POST_NOTIFICATIONS` 为运行时权限，未授予则**所有通知
  （含常驻前台通知）静默不显示**，而前台服务照跑——必须首启申请。
- **Android 15（API 35）对 targetSdk 35 的 dataSync 前台服务有 6 小时/24 小时累积
  运行限制**：超时系统回调 `onTimeout`，未 stopSelf 抛 Fatal Exception，之后重启被
  `ForegroundServiceStartNotAllowedException` 拒绝直到用户手动打开 APP。整夜挂机场景
  必然超限 → **本设计不使用 dataSync，改用 specialUse 类型**（侧载单机应用无 Play
  审核障碍），并实现 onTimeout 优雅降级兜底。
- **网络切换**：移动网络/WiFi/Tailscale 隧道重建时 WS 会半开，需
  ConnectivityManager 监听 + 立即重连。

## 4. 总体架构

```
┌────────────────────────── 手机 (<phone-model>) ──────────────────────────┐
│                                                                    │
│  ┌────────────── UI 层（原生 Kotlin + Jetpack Compose）────────┐ │
│  │  会话列表页（三态分组）   对话页（原生 Compose 会话渲染）   设置页   │ │
│  └────────────┬─────────────────────┬───────────────┬──────────┘ │
│               │                     │               │            │
│  ┌────────────▼─────────────────────▼───────────────▼──────────┐ │
│  │                  DshRepository（单例状态源）                  │ │
│  │   会话快照 Map<sessionId,{running,title,cwd,updatedAt,origin}>│ │
│  └──────┬───────────────────────────────────────────┬──────────┘ │
│         │ 调用                                       │ 状态事件   │
│  ┌──────▼─────────┐   ┌──────────────────────────────▼─────────┐ │
│  │ DshApiClient   │   │ EventStreamService（前台服务）           │ │
│  │ OkHttp 单次调用 │   │ specialUse 类型 + 双 WS + 保活 + 通知   │ │
│  └──────┬─────────┘   └──────────────────────────────┬─────────┘ │
│         │                        ┌───────────────────▼─────────┐ │
│  ┌──────▼─────────┐              │ NotificationHelper           │ │
│  │ ApprovalReceiver│◀─通知按钮──│ 回合完成/审批/提问 三类通知   │ │
│  └────────────────┘              └─────────────────────────────┘ │
└───────────────────────────────┬────────────────────────────────────┘
                                │ Tailscale 内网 (tailnet)
                                │ https://<PC-name>.tailnet.ts.net
                ┌───────────────▼────────────────┐
                │ tailscale serve → 127.0.0.1:3080 │
                │ DSH Web GUI (dsh web)             │
                └──────────────────────────────────┘
```

职责划分原则：

- **原生负责**：会话列表、对话页（原生 Compose 渲染，§5.5）、状态机、通知、设置、审批快捷按钮——这些需要后台存活与系统级能力，且对话内容直接用 DSH RPC 消费（session.history/session.prompt/流式事件）。

## 5. 模块设计（含伪码）

### 5.1 DshApiClient —— 单次调用封装

```kotlin
// 文件: network/DshApiClient.kt  (~260行)
class DshApiClient(baseUrl: String) {
    // OkHttpClient: connectTimeout 10s / readTimeout 30s / pingInterval 30s(WS专用client)
    // POST /api/<method>；信封见 3.1；TLS 走系统信任链（ts.net = Let's Encrypt 公共 CA）

    suspend fun <T> call(method: String, payload: JsonObject, rpcId: String = uuid()): ApiResult<T>
    suspend fun respond(rpcId: String, value: JsonObject): RespondReceipt
    //   → POST /api/respond；回执 {"accepted":true} 或 {accepted:false,reason:"not-pending"|"bad-response"}

    // 业务方法（薄封装）
    suspend fun sessionList(): List<SessionSummary>             // method="session.list"
    suspend fun workspaceList(): List<WorkspaceView>            // method="workspace.list"
    suspend fun sessionCreate(cwd: String): String              // method="session.create"
    suspend fun sessionPrompt(sessionId: String, text: String)  // mode="queue", content=[{type:"text",text}], clientTimeZone=IANA
    suspend fun sessionCancel(sessionId: String)
    suspend fun respondApproval(rpcId, sessionId, approvalId, allow)
    //   value={sessionId, approvalId, outcome: allow?"allowed-once":"rejected"}
    suspend fun respondQuestion(rpcId: String, sessionId: String, answers: List<Answer>)
    suspend fun probeConnectivity(): Boolean                    // GET <baseUrl>/ 期待 2xx/3xx，TLS 握手成功
}
// ApiResult = Ok(value) | BizError(code,message) | NetError(throwable)
// RespondReceipt = Accepted | NotPending | BadResponse   ← not-pending 单列，供撤通知语义使用
```

### 5.2 EventStreamService —— 前台服务（通知可靠性核心，评审修正版）

```kotlin
// 文件: service/EventStreamService.kt  (~400行)
// ★ P0 修正：foregroundServiceType = "specialUse"（manifest 声明
//   android:foregroundServiceType="specialUse" + <property
//   android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
//   android:value="Persistent WebSocket listener for remote agent session notifications"/>
//   权限：FOREGROUND_SERVICE_SPECIAL_USE。specialUse 无 6 小时限制，覆盖整夜挂机场景。
//   兜底：仍实现 onTimeout()（若未来平台对 specialUse 加限）→ 停 WS、常驻通知改为
//   "监听已暂停，点按恢复"、stopSelf()，不抛异常；用户点通知回 APP 自动重启服务。

state:
    sessions: Map<SessionId, SessionState>
    //   SessionState{running, title, cwd, updatedAt, blank, origin, localRunningSinceMillis}
    pendingApprovals: Map<approvalId, ApprovalInfo>   // ★ 按 approvalId 键控（rpcId 在信封层，仅应答时用）
    cancelSuppressUntil: Map<SessionId, Long>          // ★ P2: 本地 cancel 后 3s 抑制窗
    coldStart: Boolean = true                          // ★ P1: 冷启动标志（内存无快照）

onStartCommand:
    1. acquirePartialWakeLock()
    2. startForeground(常驻通知, FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    3. baseline()
    4. connectLoop()

suspend fun baseline():            // ★ P1 修正后的通知规则
    r1 = api.sessionList(); r2 = api.workspaceList()
    for each s in r1.items:
        prev = sessions[s.id]?.running           // 内存快照（可能为 null）
        sessions[s.id] = merge(s, r2)
        if (prev == true && s.running == false && s.origin != "subagent"):
            // ★ subagent 过滤必须与 handleHostFrame 一致：断连窗口内子代理翻转
            //   同样会落入本补发路径，不过滤则通知轰炸在此复活
            if (coldStart):
                skip                            // 冷启动：静默（防重启误报轰炸）
            else if (now < cancelSuppressUntil[s.id]):
                skip                            // 本地 cancel 抑制窗（P2）
            else:
                notifyTurnComplete(s.id)        // ★ WS 断连重连补发（堵漏报）
    coldStart = false

suspend fun connectLoop():
    forever {
        hostWs = openWebSocket("/api/events.host")   // OkHttp WS client: pingInterval=30s
        muxWs  = openWebSocket("/api/events.mux")    //   ★ P2: 防 NAT 半开拖到 TCP 超时
        任一断开 → 该条按 1s,2s,4s,…max 60s 退避重连
        重连成功 → baseline()（走 P1 修正规则，可补发）
        // ★ P2: ConnectivityManager.NetworkCallback onAvailable/onLost
        //   网络切换 → 立即主动断开两条 WS 并 0 退避重连（不等 ping 超时）
    }

fun onEnvelope(text):               // ★ P2: 先解析信封，rpcId 取自信封层
    envelope = parse(text)          // {"type":"server-request","rpcId","method","payload"}
    frame = envelope.payload
    route(envelope.rpcId, frame)

fun handleHostFrame(frame):
    case host/session-status {sessionId, running}:
        prev = sessions[sessionId]?.running
        sessions[sessionId].running = running
        if running: sessions[sessionId].localRunningSinceMillis = now; cancelTurnNotification(sessionId)
        if (prev == true && !running):
            if (!coldStart && now >= cancelSuppressUntil[sessionId] && sessions[sessionId].origin != "subagent"):
                notifyTurnComplete(sessionId)      // ← 核心通知①
    case host/session-added {sessionId, blank, origin?}: sessions.put(...)   // 记录 origin 供过滤
    case host/session-removed {sessionId}: sessions.remove(sessionId)
    case host/agent-error {sessionId, message}: 记日志+列表徽标（不通知，避免与完成通知叠加）
    case host/workspace-*: 标记 workspace 缓存脏
    case stream/error: log + 触发该流重连          // ★ P2 补充分支

fun handleMuxFrame(rpcId, frame):
    case approval/requested {sessionId, approvalId, toolName, reason?}:
        if (sessions[sessionId]?.origin == "subagent"): skip      // ★ P1: subagent 过滤
        if (pendingApprovals.containsKey(approvalId)):
            updateExistingNotification(approvalId)                // ★ P2: replay 去重，不重响
        else:
            pendingApprovals[approvalId] = {rpcId(信封层), sessionId, toolName, reason}
            notifyApproval(approvalId)                            // ← 核心通知②
    case approval/resolved {approvalId}: pendingApprovals.remove; cancelNotification
    case question/requested {sessionId, questions}:
        key = rpcId(信封层)                                       // ★ 键控=信封rpcId
        去重/过滤逻辑同上 → notifyQuestion(key)                   // ← 核心通知③
    case question/resolved {questionRpcId}: cancelNotification
    case session/projection {key:"title"}: 更新标题缓存
    case session/event | session/subscribed | session/queue | session/jobs: 忽略
        //（对话细节由 WebView 内 GUI 自行消费，双份消费无必要）
    case stream/error: log + 重连                                 // ★ P2 补充分支

fun onLocalCancel(sessionId):       // ★ P2: APP 内停止按钮调用后立即登记
    cancelSuppressUntil[sessionId] = now + 3000ms
```

**为何列表状态由服务端推送而非 APP 轮询**：`host/session-status` 翻转即推送，
无需轮询间隔的取舍；重连后 `session.list` 兜底，两者幂等合并。

### 5.3 NotificationHelper —— 三类通知

```kotlin
// 文件: notify/NotificationHelper.kt  (~220行)
通道（Channel）设计：
    ch_events  —— "会话动态"（回合完成）      IMPORTANCE_DEFAULT，声音
    ch_approval—— "需要你审批"               IMPORTANCE_HIGH，   声音+横幅
    ch_question—— "Agent 提问"               IMPORTANCE_HIGH
    ch_service —— "后台监听"                 IMPORTANCE_LOW，    常驻静默

回合完成通知:
    notificationId = hash(sessionId) 固定       // 同会话覆盖不堆叠
    标题 = 会话标题（无标题则 cwd 尾段）
    文本 = "回合完成，等待你的输入"
    点击 → ConversationActivity(sessionId)

审批通知（核心②，用户确认要做按钮）:
    notificationId = hash("approval:"+approvalId) 固定   // ★ P2: replay 去重的第二道保险
    标题 = "审批请求：" + toolName
    文本 = reason 或 "会话需要执行敏感操作"
    actions = [ 允许一次 | 拒绝 ]
    // ★ P2: PendingIntent extras 自包含全部应答字段（进程被杀后 Receiver 不依赖内存表）：
    //   extras = { rpcId, sessionId, approvalId, outcome }   ← Receiver 直接可用
    按钮 → ApprovalReceiver(broadcast) →
        receipt = api.respondApproval(...)
        Accepted      → 撤通知
        NotPending    → 撤通知 + Toast "该审批已在别处处理"     // ★ P2: 不重试
        BadResponse   → 通知文案改 "应答失败，请打开 APP 重试"
    锁屏可见性 = VISIBILITY_PRIVATE

提问通知:
    notificationId = hash("question:"+rpcId)
    标题 = "Agent 提问：" + 问题首条前 30 字
    点击 → ConversationActivity（GUI 内呈现答题卡）   // v1 不在通知上直接答
```

### 5.4 会话列表页（原生，硬需求 2.2/2.3 的主场）

```kotlin
// 文件: ui/SessionListScreen.kt (~300行) + ui/SessionListViewModel.kt (~150行)
Compose LazyColumn，数据 = DshRepository 快照的衍生流（StateFlow）:

过滤: 隐藏 blank==true（上游惯例）与 origin==subagent 的会话

分组（固定三段，段头带计数徽章）:
    ● 跑动中   —— running==true，绿色圆点，显示"约 X 分钟"（本地翻转时刻近似，§3.3）
    ● 等你输入 —— running==false && 7 天内有人类提问（updatedAt 语义见 §3.3），琥珀圆点
    ● 空闲     —— 其余，灰圆点

每行: [状态点] 标题(粗) / cwd尾段(次级灰) / "上次提问 X 前"(右对齐)
点击 → ConversationActivity(sessionId)
下拉刷新 → 手动 session.list 重拉（平时靠推送）
顶部: 连接状态条（已连接/重连中…/不可达——来自 EventStreamService 连接态流；不可达时提示"请检查 Tailscale VPN"）
FAB「+」→ 新建会话底弹（选择 workspace → session.create(cwd)）

排版原则（解决"拥挤凑一起"）:
    行高 ≥ 64dp、水平 padding 16dp、分组间距 24dp、
    单行标题省略号、深浅色主题跟随系统。
```

### 5.4.1 会话操作：重命名 / 分叉 / 归档（2026-08-25 规划）

**背景**：App 缺 WebUI 的"重命名/分叉/归档"。经实测 host-apiproxy HTTP RPC 白名单确认三个方法可直调：
- `session.rename`：payload `{sessionId, title}` → `{title, seq}`（改会话标题）
- `session.fork`：payload `{sessionId, atSeq?}` → `{sessionId}`（从当前会话 fork 出子会话）
- `workspace.archiveSession`：payload `{sessionId}` → `{archivedSessionIds}`（归档，返回全量归档集）

> 注：WebUI 的"命令+/权限选择"走 slash command（`commands.list`/`commands.execute`），已实测 `commands.execute` 在 HTTP `/api` 下 **404 不可达**，且 `session.prompt` 发 `/permission` 会被当普通用户消息（不执行命令）——故本期**不做**命令+/权限，只做上述三个可直调的操作。

**交互**（`ui/SessionListScreen.kt` 的 `SessionRow`）：
- 每行加 `⋮` 操作按钮（行尾），点击弹出菜单（模态底弹）三选：
  1. **重命名** → 弹 `AlertDialog`（预填当前 title）→ 确认调 `session.rename` → 成功后刷新列表。
  2. **分叉** → 确认框（提示"从当前会话分叉出子会话"）→ 调 `session.fork` → 成功后刷新列表（新子会话出现）。
  3. **归档** → 确认框（提示"归档后从列表隐藏，可在归档区查看"）→ 调 `workspace.archiveSession` → 成功后刷新列表（该会话从当前列表移除）。

**ViewModel（`SessionListViewModel`）新增**：
```kotlin
fun renameSession(sessionId, title, onDone)
fun forkSession(sessionId, onDone: (sessionId?) -> Unit)   // 返回新子会话 id，可跳转对话
fun archiveSession(sessionId, onDone)
// 各方法调 DshRepository.apiClient 对应 RPC；成功后 requestManualRefresh() 刷新快照
```

**DshApiClient 新增 3 个方法**：
```kotlin
suspend fun sessionRename(sessionId, title): ApiResult<Unit>          // call("session.rename", {sessionId, title})
suspend fun sessionFork(sessionId): ApiResult<String>                 // call("session.fork", {sessionId}) → 取 value.sessionId
suspend fun workspaceArchiveSession(sessionId): ApiResult<Unit>       // call("workspace.archiveSession", {sessionId})
```

**验证**：模拟器长按/点 ⋮ 菜单，分别对测试会话执行重命名（改标题）、分叉（生成新会话）、归档（会话从列表消失），逐个截图确认。

### 5.5 ConversationActivity —— 原生对话页（M5 修订：由 WebView 改为原生 Compose）

> **2026-08-25 修订**：原方案是 WebView 加载 DSH Web GUI。实测（CDP 诊断，非推测）证实该方案
> 无法交付：① DSH GUI 是工作区优先的 SPA，无会话级 URL 深链；② GUI 对窄视口布局塌陷
> （`#root` 高度 0、首屏按钮在视口外 y=-49）；③ GUI 用 CSS module **哈希类**
> （如 `_54WpYG_rail`/`pXSMma_workspace`），app 端无法按类名注入移动端 CSS。
> → 改为 **原生 Compose 会话渲染**：App 直接消费 DSH RPC（session.history / session.prompt /
> session.cancel / respond）+ mux 流的 session/event 帧，自身渲染对话，不依赖桌面 GUI。

```kotlin
// 文件: ui/ConversationActivity.kt (~320行) + ui/ConversationViewModel.kt (~260行)
布局: 原生顶条（返回 / 标题 / 停止按钮[running时]）+ LazyColumn 消息列表 + 底部停靠区 + 输入行
     （停靠区=统计/任务/审批/提问/排队五组件：动态限高=实时剩余高度×45% + 可滚动，
       提问卡等内容超高时不再压扁消息列表/顶出输入行——"提问窗卡死"修复，见 docs/fix-question-window-stuck.md）

数据:
  - 打开时: api.sessionHistory(sessionId) → 渲染历史消息（user/assistant/tool 三类）
  - 实时:   service 把 mux 流的 session/event 帧发布到 DshRepository.sessionEvents[sessionId]
             → ViewModel 收集追加（assistant/message·user/message·tool/result·turn/start-end）
  - running: repository 会话快照（host/session-status 推送）驱动"正在思考…"与停止按钮显隐

渲染（按事件类型）:
  user/message       → 右对齐气泡（文本）
  assistant/message  → 左对齐气泡（text 块渲染文本；```代码段→等宽代码块；tool-call→工具调用徽标）
  tool/result        → 可折叠"工具结果"块（join content 字符串）
  turn/start|end/step → 更新 running/thinking 状态

发送: TextField → api.sessionPrompt(...) → 乐观插入用户消息 → stop 时 notifyLocalCancel+cancel
输入: v1 纯文本；v1 不做流式增量（running 时显示"正在思考…"，消息按 settled 事件呈现）
其中实时帧路由：DshRepository.sessionEvents (SharedFlow<ConversationEvent>)，
EventStreamService MUX 分支 publish；与状态机通知逻辑并行（通知继续，另加渲染分流）。

### 5.5.2 Markdown 渲染升级 —— 自实现渲染器 → 封装渲染库（2026-08-25 规划）

**背景**：现有 `MessageBubble->MarkdownText` 是自实现的轻量渲染（标题/加粗/行内代码/代码块/列表/图片），
**不支持表格**等复杂结构，用户实测复杂 Markdown（如表格）渲染不对。

**选型结论（调研依据）**：

| 候选 | 支持表格 | 图片 | 兼容性（Compose BOM 2024.09.03 / Kotlin 2.0.20 / minSdk 26） | 结论 |
|---|---|---|---|---|
| `com.github.jeziellago:compose-markdown`（JitPack） | ✅ | ✅ 可接 Coil ImageLoader | ✅ 要求 Compose compiler 1.5.12+/minSdk 21，**与现有 BOM 最稳** | **首选** |
| `com.mikepenz:multiplatform-markdown-renderer` | ✅(>=0.30 原生) | ✅ 配 Coil2 | ⚠️ 活跃迭代、对 Compose/Kotlin 版本要求较高，可能需升级 BOM | 备选（风险高） |

**预期改造点（`ui/ConversationActivity.kt`）**：
1. `build.gradle.kts` 依赖加 `com.github.jeziellago:compose-markdown:<ver>` + `settings.gradle.kts` 加 JitPack 仓库。
2. 把自实现的 `MarkdownText`/`CodeBlock`/`highlightCode`/`inlineAnnotated` 等改为调用库的 `MarkdownText(markdown, ...)`；
   Markdown 图片加载可经库的 `imageLoader` 参数传入 Coil ImageLoader（复用现有 Coil 配置）。
3. 保留 `ConversationViewModel` 现有的流式增量聚合逻辑**不变**（只换渲染层，不动数据流）。
4. 思考/工具参数/结果的**自适应折叠**保留在渲染层外围（仍是自定义卡片/折叠容器，仅内部正文用库渲染）。

**实测结论（2026-08-25，App 端模拟器实测，非 WebUI）**：
- jeziellago 0.7.2：表格渲染成格子、标题/加粗/行内代码/列表/代码块/引用全部正常（截图 `docs/screenshots/md_user_bubble.png`）；图片因 `10.0.2.2` 地址不可达未显示（属地址问题非渲染器）。
- mikepenz 0.23.0 / 0.30.0：表格实测**仍显示成带 `|` 的文本**（未渲染成格子），其余标题/加粗/行内代码正常（截图 `docs/screenshots/mk_user_bubble.png`、`mk030_table.png`）。
- **结论：选定 jeziellago**（App 端开箱即用、全要素渲染达标，表格是核心需求；mikepenz 表格实测不生效且配置/版本更折腾）。
- 依赖：`com.github.jeziellago:compose-markdown:0.7.2`（JitPack 仓库已加）；渲染为 `dev.jeziellago.compose.markdowntext.MarkdownText`。

**风险与验证**：
- 库实际兼容性须**编译验证**（编译冲突风险点：Compose 版本匹配）。
- 替换后**自实现渲染器删除**（避免双实现漂移），单测若引用该渲染器需同步处理。
- 验收：模拟器断点发一条含**表格 + 图片 + 代码块 + 列表**的 markdown，截图确认表格正确渲染。
- **备注**：渲染库对**流式增量**的天然支持不足，靠外层 `AssistantChunkDelta` 聚合整段重渲染兜底（现有逻辑方向不变）。



**流式输出**：解析 mux `assistant/chunk` 的 text-delta/reasoning-delta，累加到"当前助手消息"；
收到 settled `assistant/message` 时以最终内容替换（去重）。running 期间实时可见，
不再只有"正在思考…"。→ ConversationEvent 增 AssistantChunkDelta(textDelta?, thinkingDelta?)

**语音输入**：输入行加麦克风按钮 → SpeechRecognizer（android.speech）→ onResults 填入输入框
（不自动发送，便于编辑）。需要 RECORD_AUDIO 运行时权限；无识别服务时 Toast 降级。

**跳到底部按钮（2026-08-25 需求）**：类似 WebUI 的"跳到最底部"。交互：
- 定义 `isNearBottom`：`lastVisibleItemIndex >= messages.size - 3`（接近底部）。
- **在底部**：隐藏按钮，新消息/流式自动 `animateScrollToItem` 跟随（现有逻辑，享受自动滚动）。
- **上翻离开底部**：显示一个悬浮「⬇ 跳到底部」按钮（列表底部右下角）。
- **点按钮**：`animateScrollToItem(messages.size - 1)` 滚到底 + 恢复自动滚动（回到底部态自动隐藏按钮）。
- 实现：用 `LaunchedEffect`/`snapshotFlow` 监听 `listState.layoutInfo` 更新 `showJumpToBottom`（仅当非底部时 true），按钮 `Box` 叠加在 LazyColumn 上方（`align(BottomEnd)`）。

**内联审批/提问卡（规划，下一步）**：approval/requested / question/requested 帧在会话内联展示
允许/拒绝或选项（与通知按钮同语义），点选走 respondApproval/respondQuestion。

**与主机 Web UI 平价路线（较大工程，需范围拍板）**：
  工具卡片富视图（toolEventView）、模型选择器、消息反馈、语法高亮等。
  优先级建议：内联审批/提问卡 > 工具卡片 > 模型选择 > 反馈/高亮。
```

### 5.5.3 三 bug 修复：注入精简显示 / 滚真底 / 插话对齐 WebUI（2026-08-26 实施）

> 方案全文见 `docs/FIX-context-scroll-steer.md`（用户确认版 + 独立评审合入项）。要点：

**① 上下文注入精简显示（修复注入全文刷屏）**
- `ConversationEvent.parse` 对 `user/message` 先看 `data.source`（兼容 `data.message.source` 嵌套）：
  `source` 缺失或 `kind=="user"` → 普通 UserMessage；否则归为 **ContextInjection(role,label,text)**。
- `label` 逐字对齐 WebUI `contextProvenance`：session-reference→`references[].label`、
  agent-instructions→`changes[].path`、plugin→`source.plugin`、skill-invocation→`source.name`、
  未知→kind；去重保序 `", "` 连接，读不到→null。`role`：session-reference→"recall"，其余→"inject"。
- UI 新增 `Role.CONTEXT`：单行「📥 上下文注入 / 🔄 跨会话召回 · <label>」，点按展开正文（默认折叠）。

**② 进入会话滚到真底**
- 根因一：`scrollToItem(size-1)` 只把最后一条**顶边**对齐视口顶，长消息看不到底部
  → 新增 `jumpToEnd`：scrollToItem 后补滚 `remaining = (last.offset+last.size) − (viewportEndOffset − afterContentPadding)`
  （经 androidx `LazyListMeasure.kt` 实现源码验证精确贴底；`scrollBy` 边界自动钳制；假设非 reverseLayout）。
- 根因二：消息多波异步填充，少量先到消息提前消耗 `scrolledOnce`
  → 新增 `historyLoaded` StateFlow 门控首滚（loadHistory 三个结束分支都置 true，失败也放行）。
- 流式跟随与「⬇ 跳到底部」按钮同样走"滚完补剩余高度"贴真底。

**③ 插话（steer）对齐 WebUI（五点）**
1. ⚡插话按钮 `enabled = running`（对齐 WebUI `disabled:!running`），非运行中显示「仅运行中可插话」；
2. payload 只发 `{kind:"steer"}`（host 只读 kind，与 WebUI 逐字一致）；
3. `steer-unavailable`（回合窗口刚关闭）/`queue-item-not-found`（项已被消费/快照滞后）**静默收敛**不提示
   （对齐 WebUI L249-255）；其他失败提示「插话发送失败，请重试。」；
4. 成功后本地零乐观变更，排队区完全由 host `session/queue` 回显帧驱动——`DshRepository.sessionQueue`
   改为 `StateFlow<Map<sessionId, items>>` 快照镜像（评审 P1：无 replay 的 SharedFlow 重进页面拿不到
   队列，收敛失去依据）；`steering` placement 项不再出现在排队区；
5. edit/remove 失败同样提示「操作失败，请重试。」（`_actionError` 统一通道）。
- 依据：host `updateQueue` 仅当"项在 next-turn 收件箱且 agent 运行中"接受 steer，否则 steer-unavailable；
  mux 订阅建立时 host 重推全部会话的队列快照（断连自愈闭环）。

### 5.6 MainActivity 与设置页

```kotlin
// 文件: MainActivity.kt (~160行)
onCreate:
    1. ★ P1: POST_NOTIFICATIONS 运行时权限流程（API 33+）:
       if (checkSelfPermission(POST_NOTIFICATIONS) != GRANTED)
           requestPermissionLauncher.launch(POST_NOTIFICATIONS)
       onDenied → Snackbar "通知已禁用，收不到会话提醒" + "去开启"按钮跳系统设置
    2. 启动 EventStreamService（specialUse 前台服务）
    3. 底部导航：会话列表 / 设置

// 文件: ui/SettingsScreen.kt (~200行)
- 服务器地址（默认 https://<PC-name>.tailnet.ts.net，可改）
- 「测试连接」按钮 → api.probeConnectivity() → 成功/失败提示（失败附 Tailscale 检查引导）  // ★ 替代证书安装死指导
- 通知开关: [回合完成] [审批请求] [Agent提问]（独立开关，默认全开）
- 通知权限状态行（未授权时显示"去开启"）
- 电池优化引导: 不在白名单 → 一键跳系统设置
- 版本信息
```

### 5.7 数据与配置存储

- `DataStore<Preferences>`：服务器地址、三个通知开关。无数据库（会话快照为易失内存态，
  重启后由 baseline() 重建；单机单用户场景不需要 Room）。

## 6. 项目结构（多小文件原则，每文件 150–400 行）

```
dsh-mobile-app/
├── docs/DESIGN.md                 # 本文档
├── app/
│   ├── build.gradle.kts           # compileSdk 35 / targetSdk 35 / AGP 8.6.x ★P2修正
│   └── src/main/
│       ├── AndroidManifest.xml    # 权限: INTERNET, POST_NOTIFICATIONS,
│       │                          #   FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE ★,
│       │                          #   WAKE_LOCK, ACCESS_NETWORK_STATE
│       └── java/dev/dshmobile/
│           ├── MainActivity.kt            # 宿主+导航+权限流程            ~160行
│           ├── DshApplication.kt          # 通知通道初始化                 ~60行
│           ├── DshRepository.kt           # 单例状态源                     ~220行
│           ├── model/Models.kt            # 数据类(信封/帧/会话反序列化)    ~320行
│           ├── network/DshApiClient.kt    # 5.1                          ~260行
│           ├── service/EventStreamService.kt  # 5.2                      ~400行
│           ├── notify/NotificationHelper.kt   # 5.3                      ~220行
│           ├── notify/ApprovalReceiver.kt     # 审批按钮广播接收           ~130行
│           └── ui/
│               ├── SessionListScreen.kt   # 5.4                          ~300行
│               ├── SessionListViewModel.kt ~150行
│               ├── ConversationActivity.kt# 5.5                          ~260行
│               └── SettingsScreen.kt      # 5.6                          ~200行
├── app/src/test/java/dev/dshmobile/   # JVM 单元测试（同语言 Kotlin）
│           ├── ModelParsingTest.kt         # 真实帧 JSON 样例反序列化
│           ├── TurnNotificationStateMachineTest.kt  # 通知状态机全分支
│           └── DshApiClientTest.kt         # MockWebServer 信封/错误/回执
├── build.gradle.kts / settings.gradle.kts / gradle.properties
├── tools/setup_build_env.ps1       # 构建链自动安装脚本（JDK17+SDK+Gradle，国内镜像 fallback）
└── _tools/                         # 过程脚本存档（非构建产物）
```

技术栈：Kotlin 2.0 + Jetpack Compose (BOM) + OkHttp 4（含 mockwebserver 测试）+
kotlinx-serialization-json + DataStore + kotlinx-coroutines。
**不引入**：Hilt（单模块无必要）、Room（见 5.7）、Retrofit（OkHttp 直连更贴近 DSH 裸信封协议）。

## 7. 构建链方案（本机自动装机）

```
tools/setup_build_env.ps1 步骤:
1. Temurin JDK 17 (zip 绿色版) → .toolchain\jdk17
   源: adoptium API（失败 fallback 清华镜像）
2. Android commandline-tools → .toolchain\android-sdk\cmdline-tools\latest
   sdkmanager --licenses 接受
   sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
   (google 失败 fallback 腾讯镜像 mirrors.cloud.tencent.com/AndroidSDK/)
3. Gradle 8.9 (zip) → .toolchain\gradle-8.9      （AGP 8.6.x 兼容 Gradle 8.7+）
   (services.gradle.org 失败 fallback 腾讯镜像)
4. 首次构建: gradle assembleDebug --no-daemon
   maven: google() mavenCentral()（慢时切阿里云镜像）
5. 产物: app/build/outputs/apk/debug/app-debug.apk
交付: platform-tools 自带 adb 安装 or 用户手动传手机安装
```

约束：所有工具装进项目 `.toolchain\`（不污染系统），脚本幂等（已装跳过）。

## 8. 测试计划（评审修正版）

| 层 | 方式 | 覆盖 |
|----|------|------|
| 协议反序列化 | JVM 单测（kotlinx-serialization 对真实帧 JSON 样例） | 信封+帧全类型、rpcId 取信封层、畸形帧容错、not-pending 回执解析 |
| 通知状态机 | JVM 单测（注入帧序列，断言通知调用） | 冷启动抑制 / **断连重连补发（P1 修正后预期=补发）** / cancel 抑制窗 / subagent 过滤 / approvalId 去重（replay 不重响）/ running 恢复撤通知 |
| ApiClient | JVM 单测 + MockWebServer | 信封格式、错误分支、超时、回执三态（Accepted/NotPending/BadResponse） |
| 端到端 | 真机 <phone-model> 手测清单 | 首启权限申请、息屏收通知、审批按钮免开 APP、not-pending 撤通知、列表三态与 blank 隐藏、WebView 排版、改地址生效 |

语言一致性：全部 Kotlin + kotlin.test，不混其他语言。

## 9. 风险与不确定项（评审修正版）

| # | 风险 | 影响 | 对策 |
|---|------|------|------|
| 9.1 | vivo ROM 激进后台管控，前台服务仍可能被杀 | 通知不可达 | 设置页电池优化引导 + 自启动引导说明；常驻通知保可见性 |
| 9.2 | DSH GUI 的 SPA 无会话级 URL 路由（未确认） | 点通知进 APP 后需再点一次会话 | 实施时从前端 dist 确认；fallback = JS 注入按标题点击，再 fallback 手动点 |
| 9.3 | WebView 内 GUI 移动端适配程度未知 | 排版注入规则需迭代 | 5.5 策略：先观察后逐条加 CSS，验收=单手可读可输入 |
| ~~9.4~~ | ~~tailnet CA 证书信任~~ | ~~已证伪~~ | ★ 修正：ts.net 证书 = Let's Encrypt 公共 CA，系统信任链零配置；对策改为「测试连接」连通性自检（TLS 握手+HTTP 200），失败提示检查 Tailscale |
| 9.5 | session.list 的 projections.title 字段路径 | 列表标题显示 | 实施首日用真实响应定 schema 常量，单测锁死 |
| 9.6 | 构建链下载被墙/慢 | 装机超时 | 全部下载源配国内镜像 fallback |
| 9.7 | ★新增：Android 15 对部分前台服务类型的 6 小时限制 | 若误用 dataSync 类型则整夜挂机破防 | 本设计已改用 specialUse 类型（无时限）+ onTimeout 优雅降级兜底（§3.5、§5.2） |
| 9.8 | ★新增：subagent 会话行为未实测 | 通知过滤策略可能需校准 | 实施首日实测 origin=subagent 是否走 host 流翻转，按实测校准 §3.2 策略 |

## 10. 里程碑

| 阶段 | 内容 | 出口标准 |
|------|------|---------|
| M1 | 构建链装机 + 工程骨架编译通过 | assembleDebug 出空壳 APK |
| M2 | 协议层 + 单测 | 单测全绿，真机连上 API 拉到会话列表 |
| M3 | 前台服务(specialUse) + 三类通知 | 真机息屏收到回合完成通知；审批按钮免开 APP 生效 |
| M4 | 会话列表页 + 设置页 | 三态分组正确、blank 隐藏、地址可改、开关生效 |
| M5 | WebView 对话页 + 排版注入 | 真机可看可发消息，排版验收通过 |
| M6 | 端到端验收 + 文档同步 | 交付 APK + 使用说明 |

## 11. 完成度

- [x] 需求确认（§2）
- [x] 源码级协议调研（§3）
- [x] 架构与模块设计（§4–7）
- [x] 对抗评审 + 仲裁（v1.0 → 1×P0/4×P1/12×P2）
- [x] 评审修正落实（v1.1，对照表见 §12）
- [x] 修正后复审（fix-then-pass，两处修复已落实）
- [x] 实施 M1：构建链装机 + 空壳 APK（2026-08-24）
- [x] 实施 M2：协议层 + 单测（双 Agent 对抗评审 A/B 修复后通过，54/54 绿）
- [x] 实施 M3：前台服务 + 三类通知（状态机 16 例全分支单测）
- [x] 实施 M4：会话列表页 + 设置页 + 主入口
- [x] 实施 M5：WebView 对话页 + 排版注入 + 停止按钮抑制窗
- [x] 实施 M6：终审（M3–M5 评审 P0/P1 清零）+ 模拟器 E2E 五连测 + 交付收口（2026-08-24）

实施偏差记录（相对 §5 设计，均有据）：
- §5.1 DshApiClient 增补：事件流登记簿/世代号/closeEventSockets 与 shutdownEventStreams 拆分
  （评审 B P1-1/P1-4 修复）；RespondReceipt 增 Transport 态（评审 B P1-3）；
  respond 走 5s/8s 短超时专用客户端（终审 P1-4 ANR 防护）；
  debug 构建 Host 改写拦截器（模拟器 E2E：10.0.2.2 非 loopback 主机名，改写为受信权威过信任门）
- §5.2 增补：WS readTimeout 90s（>2×ping，评审 B P1-2）；重启用轻关不关执行器（终审 P1-3）；
  onFailure/onClosed 失败原因日志（模拟器排障观测点）
- §5.5 增补：外链跳系统浏览器（终审 P2）；WebView onDestroy destroy（终审 P2）
- §6 文件清单微调：新增 data/SettingsStore.kt、DshRepository.kt（单例状态源从 EventStreamService
  拆出，服务与 UI 解耦）；debug 源集 AndroidManifest + network_security_config（模拟器明文 HTTP）
- 模拟器 E2E 证据：docs/screenshots_session_list.png、docs/screenshots_conversation.png；
  测试方案细节见 PROGRESS.md 文首断点段

## 12. 评审修正对照表（v1.0 → v1.1）

| 级别 | 问题 | 落实位置 |
|------|------|---------|
| P0 | dataSync 6 小时限制 vs 整夜挂机 | §3.5、§5.2（specialUse + onTimeout 兜底）、§9.7、§6 权限清单 |
| P1 | 断连窗口完成回合永久漏报 | §3.2 表、§5.2 baseline() 冷启动判定重构、§8 测试预期反转 |
| P1 | POST_NOTIFICATIONS 运行时申请缺失 | §5.6 MainActivity 流程 + 设置页状态行、§8 端到端首项 |
| P1 | subagent 通知/列表策略未定义 | §3.2 subagent 行、§5.2 过滤逻辑、§5.4 过滤、§9.8 实测校准 |
| P1 | 9.4 证书前提错误（实为 Let's Encrypt） | §3.4、§9.4 重写、§5.1 probeConnectivity、§5.6 测试连接 |
| P2 | 伪码 rpcId 写在帧内 | §3.1、§5.2 onEnvelope/handleMuxFrame 全面改为信封层 |
| P2 | mux replay 重复响铃 | §3.2、§5.2 approvalId/rpcId 键控去重 + §5.3 固定 notificationId |
| P2 | not-pending 误设计为重试 | §3.1 回执语义、§5.1 RespondReceipt、§5.3 撤通知 + §8 用例 |
| P2 | PendingIntent 依赖内存表 | §5.3 extras 自包含应答字段 |
| P2 | AGP 8.5 不支持 compileSdk 35 | §6（AGP 8.6.x）、§7（Gradle 8.9） |
| P2 | "已运行时长"无数据源 | §3.3 本地翻转时刻近似 + 标注"约"、§5.2 localRunningSinceMillis |
| P2 | updatedAt 语义误读 | §3.3 校准（"上次提问"文案 + 分组条件）、§5.4 |
| P2 | blank 会话应隐藏 | §3.2、§5.4 过滤（对齐上游惯例） |
| P2 | 缺 stream/error 分支 | §3.2、§5.2 两处分发表补齐 |
| P2 | WS 保活缺失 | §5.1 pingInterval=30s、§5.2 ConnectivityManager 网络切换立即重连 |
| P2 | "IP 直连不可行"论证过强 | §3.4 精确表述（仅 0.0.0.0 被禁；结论不变，论据修正） |
| P2 | cancel 误报为完成 | §3.2、§5.2 cancelSuppressUntil 3s 抑制窗、§5.5 停止按钮登记 |
