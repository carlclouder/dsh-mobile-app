# DSH Mobile APP — 任务进度台账（断点续跑依据）

> **开源清理说明（2026-09-01）**：全部验证截图已移至 `docs/private/screenshots/`（git 忽略），文中截图路径引用指向该私有目录。

> **规则**：每完成/变更一步**立即**更新本文件；任何新轮次（含会话中断恢复）**先读本文件**再决定下一步。
> **状态符号**：`[ ]` 未开始 | `[~]` 进行中 | `[x]` 完成 | `[!]]` 失败/受阻（附原因）
> 机读构建链状态：`.toolchain\setup_state.json`（装机脚本自动维护）

| 字段 | 值 |
|---|---|
| 目标 | goal-2bad2bf7（本会话重建；原 goal-ad225633 属旧会话） |
| 设计文档 | `docs/DESIGN.md`（v1.1.1 评审修正 + §5.5 原生对话页修订 + §2.9 新增需求） |
| 产物根 | `<repo>\` |
| 最后更新 | 2026-08-26（三 bug 修复轮已提交 commit d781ff5：①上下文注入精简为单行来源显示 ②进会话滚真底 ③插话对齐 WebUI 五点 + sessionQueue StateFlow 快照镜像 + 双代理评审通过 + 功能对齐审计交付 docs/AUDIT-webui-alignment.md；共 12 文件 +784/−67） |
| 当前版本 | `app/version.properties` → versionCode=104 / versionName=0.1.103（**每次 assemble 自动 +1**；**用户只装 release，需配套生成 release 版**） |
| 单测 | 71/71 绿（+9：注入分类 8 例含空引用降级回归 + steer payload 形状 1 例；`testDebugUnitTest` 实测 fresh_tests=71 failures=0） |

---

## ⚡ 当前断点（新轮次从这里继续，必读）

**状态：新版 dsh(≥0.1.2-rc.1) 令牌认证适配——第一阶段完成（2026-09-16）——宿主 dsh 升级后启用一次性令牌认证（规则 21：令牌=入门钥匙一次性，Cookie=通行证 30 天），App 全部请求 401"不可达"。已实现：①新增 network/DshAuthSession.kt（地址拆分 base/?token=、令牌消费状态机 RAW→CONSUMED、Cookie SharedPreferences 持久化 30 天复用、authInterceptor 注入+401 自愈、双客户端 Host 口径一致）；②DshApiClient 地址解析接入（构造/updateBaseUrl 均走 splitBaseUrl）、httpClient/respondClient 挂 authInterceptor、openEventStream WS 握手注入 Cookie、停用旧版 debug Host 改写（新版围栏实测不检查 Host，改写反而致 Cookie JWT authority 与请求 Host 不一致判 401——okhttp 4.12 HttpUrl 无 defaultPort 实例属性只有伴生函数）；③新增宿主工具 tools/dsh-auth-proxy.mjs（自动登录代理：从 dsh 启动日志提取最新令牌换 Cookie、转发注入、401 自愈、WS upgrade 转发，实测 3083→3082 全链路通）。实测：设置页粘贴 `http://10.0.2.2:3080/?token=<当前令牌>`（令牌取自 C:\Users\Carl\.dsh\logs\dsh-webui.log）→ 测试连接 ✅。**发现新问题（下一步适配）**：新版 dsh 已移除 /api/events.mux|events.host WS 事件流端点（带 Cookie 实测 404/断流，WebUI 自身改 HTTP 轮询——浏览器 network 实证无 WS 有轮询），App 的 WS 事件流架构在新版宿主上不可用 → 会话列表/实时刷新/通知失效需改轮询适配（工作量中等，待用户确认后动工）。证据 test-evidence/dsh-token-20260904/。单测 101/101、assembleDebug 绿。**

**状态：v0.1.150 气泡紧凑排版 + markdown 标题治理（2026-09-04，版本号因本轮多次 assembleDebug 累涨，实际产物 v0.1.150；首次 debug 构建时曾记 v0.1.136，代码同一份）——用户反馈"气泡内部行间距/顶底留边太大"修复：①MarkdownText 行距 setLineSpacing(0,1.25f)+includeFontPadding=false（库内部是 AndroidView TextView，走 afterSetMarkdown 回调）；②气泡上下 padding 4→2dp、时间戳 11→10sp、消息间 spacedBy 6→4dp/列表上下 6→3dp；③移除 markwon HeadingSpan（标题 1.6~2 倍爆版面）改加粗+1.15 倍——注意 markwon 渲染产物是不可变 SpannedString，必须复制 SpannableStringBuilder 改后回写（`as? Spannable` 转换会失败，曾误判无 span）；④isTextSelectable true→false（可选 TextView 消费全部触摸致气泡区无法滚动列表，关掉后 LinkMovementMethod 链接仍可点）。关键 API：jeziellago MarkdownText 有 beforeSetMarkdown(tv,spanned)/afterSetMarkdown(tv) 回调（javap 签名实证）。模拟器实测：标题单行加粗、行距紧凑、空正文占位已消（空正文跳过渲染）、流式思考/展开/落定回归正常。截图 test-evidence/bubble-compact-20260904/。未验证：真机观感；MarkdownText 长按选择因 isTextSelectable=false 不再可用（SelectionContainer 不覆盖 AndroidView）。**

**状态：v0.1.134 思考折叠行流式滚动摘要完成（2026-09-04）——需求"折叠态像 WebUI 一样单行横向滚动刷新最新末尾思考语句、区分思考与卡住"已实现并 E2E 实证。新增 ui/ThinkingStreamRow.kt（💭 思考中 + thinkingLatestLine 末行摘要 + horizontalScroll 钉尾 scrollTo(maxValue) + 2.6s 扫光光带 + ANIMATOR_DURATION_SCALE 减少动画尊重；ConversationActivity.CollapsibleThinking 加 streaming 参数，仅 STREAMING_KEY 占位消息走新行，落定/历史不变）。单测 101/101 绿（+11 ThinkingStreamLineTest：latestLine 7 + 扫光 clamp 4）；双包构建 v0.1.134。模拟器 E2E 三帧实证：思考增长期摘要贴尾→思考结束摘要更新（两帧文字不同均贴尾）+ 扫光光带可见 + 落定回退静态文案，截图 docs/screenshots/thinking_stream_e2e_1/2/settled.png。方案 docs/fix-thinking-stream-preview.md（两轮评审+代码评审通过）。场景测试补齐（S1 连续帧/S2 扫光/S3 落定/S4 流式中展开/S5 收起/S6 历史行）证据归档 test-evidence/thinking-stream-20260904/。未验证：真机观感。注意：测试/构建必须走 D:\dshm ASCII 联接路径（中文原路径 Gradle 测试进程类加载全红，评审实证）。**

**状态：v0.1.132 模型/代理调用错误展示修复完成并 E2E 实证（2026-09-01，待提交后清除本行状态）——host/agent-error 帧与 turn/end 错误原因渲染为会话内红色气泡「⚠ 模型调用错误」。E2E 方法：宿主历史含 2 次真实 429 额度错误（code 1310，zai 周/月配额），session.fork atSeq=177057 分叉出错误在末尾的会话，App 打开分叉实测：错误气泡标题+1310 全文渲染于对话底部（截图 e2e_v132_agent_error.png）。挂账新增：超大历史会话（21 万事件截尾 100 条）进入时首屏未自动贴底（需点跳到底部）——与既有滚底历史 bug 同类，待专项。**

**状态：v0.1.130 三组修复验证完成（2026-08-31 凌晨）——①工具卡/结果卡 WebUI 对齐（宿主 view.view.title 单行摘要贯通 history+live 双管线，默认单行折叠点按展开；模拟器实测折叠态长正文节点 0、标题+摘要间距 6px）②重连自愈（成功连接重置退避+上限 60s→15s+前台 ON_RESUME 未连接即踢立即重连；模拟器断网→恢复 4 秒自动重连，无需重启）③语音输入降级通道（服务通道失败自动转 RECOGNIZE_SPEECH 识别活动由厂商/讯飞应用接管+manifest queries 可见性+错误码可确诊）。单测 90/90、双包构建。v0.1.126 已 commit(e147c10)，本批待真机复核后 commit。挂账不变：P1 仓库文档私人信息、表格/mermaid 渲染、P2 清单。**

**状态：新建会话可见性问题四层修复全部闭环（2026-08-30 凌晨，v0.1.126）——①running 清 blank（v119）②时区净化救发送（v122）③ON_RESUME 对账+blank 单向锁存+SessionAdded 合并（v125，协作升级定稿，锁存种子 bug 被单测当场抓住并修）④发送受理即标记（本地事实，v126）：列表对已发过消息的 blank 会话豁免隐藏。模拟器时序矩阵全绿：T1 不发送返回→隐藏 ✓；T2 发送后 0.6 秒即返回→1 秒内可见 ✓（修复前 2-4 秒=宿主 agent 启动延迟）；T3 已发送会话持续可见 ✓。单测 90/90。全批（v117-v126 累计：气泡紧凑/插话防重/隐私外置/关于页/IME/跳底/时区/可见性）待用户真机复核后一并 commit。挂账不变：P1 仓库文档私人信息（开源前必清）、表格/mermaid 渲染、P2 清单。**

**状态：v0.1.123 全批验证完成（2026-08-29 傍晚）——A 气泡紧凑化（气泡顶→首行 14px 达标/思考单行折叠）、B 插话防连点（评审确认 VM 层同步挡死）、C 隐私外置（源码零私人信息+personal.properties 机制实证）、D 关于页可复制、E 新建→发送→返回→列表立即显示（三次报障路径模拟器闭环：vis 会话返回即现）、F 重装零输入机制链（默认域名注入 APK 实证）。单测 88/88、编译 0 错、全量编译前评审放行（0 P0）。待用户真机复核后一并 commit。挂账：①协作升级产出的加固项未实施（ON_RESUME 对账/blank 单向锁存/SessionAdded 合并——防慢链路丢帧场景）②P1 仓库文档私人信息（开源前必清）③表格/mermaid 渲染不支持（等用户拍板方案）④P2 清单见评审报告。**

**状态：三轮修复全部实施并验证（2026-08-29 深夜，v0.1.122）——①提问窗卡死三症状 ②工具结果空内容/叠词/图标 ③IME 顶条/跳底贴真底/留边+气泡对比/关于页/新建会话语义/发送静默失败(invalid-time-zone)。单测 88/88 绿；模拟器实证：发送→回复 pong 全链路、IME 顶条原位、气泡/页面色差 23 阶、空会话返回隐藏、工具结果有正文、图标黑白（APK 内像素）。未验证项（明说）：真机全量行为、叠词偶发场景（真机链路）、跳底按钮完整点击流、发送后返回列表可见链路。全部待用户真机复核 v0.1.122 后一并 commit。关键根因记录：①宿主 tool-result.content 是类型化块数组 ②宿主 clientTimeZone 只收 UTC/IANA 名（模拟器 GMT+08:00 被拒→发送静默失败→会话永远 blank）③默认主题 surfaceVariant 与页面底色同色（留边过宽感知根因）④WebUI 空会话语义=仅当前可见（sessionVisible L101），宿主无 dispose API。**

**状态：第二轮三修复已实施（2026-08-29 深夜）——①工具结果空内容 ②流式叠词 ③图标黑+白。单测 85/85 绿（+4 解析回归 +5 去重器）、编译 0 错、产物 v0.1.116（真帧/像素级实证见下）。两轮修复（v0.1.115 提问窗卡死三症状 + v0.1.116 本轮）均待用户真机复核后一并 commit（用户指示"真机后提交"）。**

> **第二轮修复（2026-08-29，v0.1.116）**：
> - **bug 工具结果空内容**：宿主 `tool-result.content` 实为类型化块数组 `[{type:"text",text}]`（本会话 history 45/45 帧实证，对照 dsh-llm ContentBlockMap），旧解析只认字符串数组→对象元素全丢→输出恒空。修复=`extractToolOutput` 重写（块数组 text 提取 + 图片/未知块占位 + 旧字符串数组兼容）+ `CollapsibleToolResult` 空内容不渲染兜底 + 真帧回归单测 4 例。
> - **bug 流式叠词（一个词重复两次/整句每词重复）**：宿主日志 24892 事件 seq 全唯一严格递增（实证不重发）；叠词来自传输/重连层偶发重复投递（手机 Tailscale 慢链路高发），chunk 文本追加不幂等。修复=新增纯类 `service/SessionEventDeduper.kt`（每会话 seq 水位去重，对齐宿主 session/subscribed.lastSeq 基线语义）接入 EventStreamService session/event 发布口 + 单测 5 例；迟到重复帧不再进 UI 流（同时挡住"settled 后迟到片段重建占位"的叠词形态）。
> - **图标蓝+白→黑+白**：5 密度 PNG 逐像素重着色（按"蓝色颜料占比"公式：纯蓝→黑、白保留、抗锯齿边→灰阶；像素级复核 bg=10,10,10 / glyph=255,255,255 / 圆角透明保留）。
> - 版本 v0.1.116（versionCode 117）；安装包 `app\build\outputs\apk\release\DSH-Mobile-release-v0.1.116.apk`。

**状态：提问窗卡死三 bug 修复已实施并通过全部验证（2026-08-29 晚）——单测 76/76 绿、编译 0 错、模拟器 E2E 三症状闭环（滚动前后 dump 对比：第二题+提交按钮初始不可见→滚动后可达；发送/输入框恒在屏；提交后卡片消失 agent 继续）、双评审通过。产物 v0.1.115（debug+release）。提交待用户真机复核后执行（用户指示"真机后提交"）。方案全文 `docs/fix-question-window-stuck.md`，E2E 截图 docs/screenshots/e2e_4/5/6。**

> **提问窗无法翻动/遮挡按钮/会话页面永久卡住 修复轮（2026-08-29）**：
> - **根因A（布局）**：底部停靠区（统计/任务/审批/提问/排队+输入行）全是 Column 非加权固定子项、无上限不可滚——ask_user_question 载荷超一屏时消息列表被压到 0 高、输入行顶出屏幕、提问卡超屏部分不可达，agent 等答+用户无法作答=死锁。修复=停靠区包 `BoxWithConstraints{Column(heightIn(max=剩余高×45%)+verticalScroll)}`（嵌在 Column 内，maxHeight=视口−顶条−分隔线，评审已核 Compose 1.7.3 测量语义生效）。
> - **根因B（链路）**：respondQuestion/respondApproval 丢弃 RespondReceipt+无条件乐观移除——传输失败卡片静默消失且"流连接且无帧"窗口内不恢复=第二条卡死路径（审计 Top3-② 前半）。修复=新增纯函数 `ui/RespondReceiptPolicy.kt`（Accepted 静默/NotPending 提示已在别处处理/其余恢复卡片+Toast 重试，语义对齐 ApprovalReceiver），VM 按三态收敛。
> - **顺带修（用户确认）**：QuestionCard 多问题共用一个 custom 输入状态（审计 Top3-② 后半）→ 改按 q.id 分存 Map。
> - **测试**：新增 RespondReceiptPolicyTest 5 例（五态回执→三决策全分支），全量 76/76 绿；assembleDebug/Release 编译 0 错。
> - **挂账（本轮发现，评审 P2-3）**：前台服务/WS 停摆期间到达的 question/approval 帧永久丢失——状态机只由帧填充、baseline 无提问字段、无待答查询 API，客户端无解，需服务端补接口（待用户拍板）。
> - **E2E 模拟器坑位记录**：adb `input keyevent 111`(ESC)=BACK 会弹走设置页导致"保存"没点到（改为不按 ESC 直接点保存）；模拟器进程可能自行退出需重启（AVD dsh_test，app 数据含已存地址持久）；本轮 E2E 截图 docs/screenshots/e2e_*.png。

**状态：三 bug 修复已提交（commit d781ff5，2026-08-26）——编译绿 + 单测 71/71 + 双代理评审通过。功能对齐审计已交付（docs/AUDIT-webui-alignment.md，41 项：✅15/⚠️21/❌5）；审计修复项待用户拍板后另开方案周期（Top3：发送失败丢消息 / 审批提问回执三态+多问题custom共用 / 历史分页"加载更早"）。挂账：ConversationActivity.kt 拆分（超 800 行，HEAD 时已 934）、VM 层 steer 分流单测、插话"已插话·待注入"展示（FIX 文档 §3）。**

> **点会话闪退修复（2026-08-29，commit 16efc44，release v0.1.112）**：用户真机报障"点会话弹回桌面"。模拟器 100% 复现：`collectQueue` 改为消费 StateFlow 快照后，`StateFlow.collect` 在 VM 构造期间（Main.immediate）同步发出当前值，而 `_queuedItems` 声明在 init 块之后尚未初始化 → NPE（旧代码是 SharedFlow 会挂起所以侥幸不炸——正是评审 B P2-5"VM 无单测"警告的盲区）。修复=字段声明挪到 init 之前（与 `_approvals/_questions` 同模式，文件内既有注释早已警示该坑）。回归：模拟器 debug 构建闪退场景复现→修复→多次进出会话无崩溃、对话页正常渲染（截图 docs/screenshots/verify_conv_0.1.110.png）；release v0.1.112 安装启动正常（模拟器无 tailnet 路由无法全链路；R8 不改变字段初始化序、风险极低）。VM 构造序 JVM 单测并入 P2-5 挂账（DshRepository 单例依赖 Android Context 暂不可测）。注意：release 目录可能出现"旧 app-release.apk 被改名成新版本号"的假新包（renameApks 在 debug 构建后也会跑），交付前必须 assembleRelease 出真包并按构建时间核对。

> **三 bug 修复轮（2026-08-26，已提交 d781ff5）**：
> - **bug1 上下文注入刷屏**：`ConversationEvent.parse` 对 user/message 按 `source.kind` 分类（缺失/"user"→UserMessage；其余→`ContextInjection(role,label,text)`）；label 提取逐字对齐 WebUI contextProvenance（agent-instructions→changes[].path 等，去重 join）；UI 新增 `Role.CONTEXT` 单行「📥 上下文注入 · <路径>」点开显示全文。生产端结构实证：dsh-agent-instructions L755-767。
> - **bug2 进会话不滚底**：根因①`scrollToItem` 只对齐最后一条顶边→新增 `jumpToEnd`（补滚 `viewportEndOffset-afterContentPadding` 剩余高度，经 androidx LazyListMeasure 实现源码验证精确贴底）；根因②消息多波填充提前消耗 scrolledOnce→新增 `historyLoaded` 门控（loadHistory 三分支都置 true，失败放行）。流式跟随/跳底按钮同步贴真底。
> - **bug3 插话不对齐**：五点修复——空闲禁用⚡+「仅运行中可插话」提示；payload 去 target 只发 `{kind:"steer"}`；steer-unavailable/queue-item-not-found 静默收敛（对齐 WebUI L249-255）；成功后零本地变更纯 host 回显驱动（消除乐观删除与回显打架的残留/闪烁）；edit/remove 失败补提示（_actionError 统一通道）。
> - **评审 B P1 合入**：`DshRepository.sessionQueue` 从无 replay 的 SharedFlow 改 `StateFlow<Map<sessionId,items>>` 快照镜像——修复"重进对话页队列区恒空"（插话/编辑/删除收敛的依据）。
> - 单测 +8（注入分类 7 例 + steer payload 形状 1 例），评审后 +1（空引用 label 降级回归），共 71。
> - **双代理评审结论：A 通过（无 P0/P1）；B"修复后通过"（条件全部处置）**：P0-1（scrollBy import）被实证撤销——import 先于评审已补、三次构建全绿；P1-1（方案⑤）按修订文档处置（移出 scope，见 FIX 文档 §3）；P1-2（ConversationActivity 超 800 行，HEAD 时已 934）**挂账**：下一轮做专用重构提交（抽 MessageBubble 族到独立文件）；P2-1 update{} 原子合并/P2-3 注释去轮次引用/P2-4 Surface onClick 已修，P2-2（map 生命周期）/P2-5（VM 层单测欠账）已在注释与本文档挂账。
> - 方案九步：①-⑨ **全部完成**（⑧ 用户确认提交；⑨ commit d781ff5）。
> - **功能对齐审计已交付**（docs/AUDIT-webui-alignment.md）：可比 41 项——✅15 / ⚠️21（中高·中 6）/ ❌5；RPC 层全部对齐；本轮三处修复被复核"逐语义一致"。修复建议 Top3 待用户拍板后另开周期：①发送失败丢消息(VM 426-442) ②审批/提问回执三态+多问题 custom 共用(VM 133-146, Activity 637-687) ③历史分页"加载更早"(DshApiClient 299)。

> **会话操作功能（重命名/分叉/归档，需求 §5.4.1，已完成 + 实测通过，release 0.1.93）** —— 三个 HTTP RPC 直调（session.rename / session.fork / workspace.archiveSession）。`SessionRow` 行尾加 ⋮ 菜单（重命名/分叉会话/归档会话）+ 各确认/输入对话框。**修复"操作成功但 App 列表不即时刷新" bug**：操作成功后 `_operationTick++`（作 rows remember key 强制即时重算）+ `requestManualRefresh`（触发 baseline 拉新快照，重命名/归档生效）；分叉成功回调新 id 自动跳转新会话对话页。**模拟器实测**：重命名不重启即显新名、分叉 host 建子会话 + App 跳转、归档 host 归档集更新 + 列表排除。已确认 `commands.execute` 在 `/api` 下 404 且 prompt 不识别 slash 命令 → **命令+/权限选择本期不做**。
- 代码：`DshApiClient`(+sessionRename/sessionFork/workspaceArchiveSession) + `SessionListScreen`(⋮ 菜单 + 3 对话框 + operationTick) + `SessionListViewModel`(3 操作 + operationTick)。
- 版本：debug/release 0.1.93（versionCode 94）。

> **跳到底部按钮 + 创建会话修复（release 0.1.96，待提交）**：
> - **跳到底部按钮**（类似 WebUI）：`showJumpToBottom` 状态监听 `listState.layoutInfo`（snapshotFlow），上翻离开底部(<size-3)时在列表底部右下显示悬浮「⬇跳到底部」，点击 `animateScrollToItem(size-1)` 滚到底并恢复自动滚动；在底部时隐藏（自动滚动正常）。首次进入默认到底（现有 scrolledOnce 保留）。
> - **创建会话修复**：「+」选工作区创建无效 bug——`createSession` 成功后原来没刷列表/不跳转；修复=成功后 `requestManualRefresh`+`_operationTick++`（列表刷新）+ **自动跳转新会话对话页**。实测：选 Desktop 工作区 → host 创建新会话(`session-d80f96cb`) + App 自动进入对话页。
> - **创建会话挂工作区修复（0.1.98）**：新会话落"未分组"根因=**`session.create(cwd)` 不会挂工作区**（只设 cwd）；`session.create(workspaceId)` 才会挂到对应工作区（host 验 `session-6d6142e4` 进 Desktop.sessionIds）。修复=`DshApiClient.sessionCreate` 改传 `workspaceId` + `SessionListScreen.onCreate(workspace.workspaceId)`（原 path）。单测同步改为 workspaceId 语义（62/62 绿）。—— 待真机确认"新会话落对应工作区分组"。
> - **插话(Steer)立即发送并清理排队（0.1.101，已提交 475c011）**：点 ⚡插话原来复制一条排队消息 + 不清理原项。修复=①`steer(itemId,text)` 成功后**本地移除该排队项**；②`collectQueue` 只显示 `placement=="queued"`（排除 steering 回显项，避免"复制一条"）。—— 注：DSH 的 steer 本身是"排队中途引导"(agent 运行中要等下一步边界才消费)，非立即打断；agent 空闲才立即启动一轮。单测 62/62 绿。
> - **插话彻底修复（三方评审一致 P0，已提交 b5b5da7）**：全面评审（A 协议/B 数据流/C UI）结论=**插话走错 RPC 通道**——用 `session.prompt mode="steer"` 发新文本 + 本地删排队项，host 从没消费该项 → 回显覆盖 → 残留/复制/不发送。**修复**=改走 `session.updateQueue {kind:"steer", target:"next-turn"}`（host 就地转换该项 placement queued→steering），`steer()` 本地乐观移除该项(成功) + 失败 Toast(steer-unavailable=agent 未在运行)；`_running` 不再强置(由 host 快照/TurnStart 权威驱动)；**实测**：消费进对话流/仅 1 次不复制/排队区清空无残留。
> - **全面评审 P1 修复（已提交 9c6a6bc）**：①P1-1 `loadHistory` 不整表覆盖 `_messages`——改按 key 合并进现有(防 history 往返期间实时消息被抹=消息闪失)；②P1-3 插话成功乐观移除排队项(host 接受则必离开 queued，立即消失+回显校准)；③P2-1 删死代码 `sessionSteer()`(插话已走 updateQueue)。—— 待办(未修)：G-22"上下文已用%"声明未写入未显示、send/edit/delete 失败静默、createSession 形参名 cwd 实传 workspaceId 命名误导、release 缺 keystore 静默降级 debug 签名、P1-2 空闲插话未兜底启动一轮。

> **对话实时滚动（0.1.88 已提交 d31ac00）**：流式期间 `messages.size` 不变导致不滚动；修复=触发键加 `lastKey`+`lastTextLen` + 接近底部才 `animateScrollToItem(size-1)`（避免上翻被拉回）。模拟器实测：进长流式会话滚到最新内容。

> **Markdown 渲染库选定 jeziellago（2026-08-25）**：双库实测对比（App 端模拟器截图）——jeziellago 0.7.2 表格渲染成格子+标题/加粗/行内代码/列表/代码块/引用全正常；mikepenz 0.23/0.30 表格仍显示成 `|` 文本。**选定 jeziellago**（开箱即用、全要素达标）。依赖 `com.github.jeziellago:compose-markdown:0.7.2`（JitPack 仓库已加），渲染 `dev.jeziellago.compose.markdowntext.MarkdownText`。截图：`docs/screenshots/md_user_bubble.png`（jeziellago 成格）、`mk_user_bubble.png`/`mk030_table.png`（mikepenz 文本）。

> **P0 卡死修复（重要）**：打开含 105,530 条事件（assistant/chunk 占 104,502 个）的巨大会话卡死。根因=每收一个 chunk 全量重建消息列表。修复=①流式聚合节流（chunk→StringBuilder，80ms 合并刷新一次 _messages.value）；②loadHistory 过滤历史 chunk 碎片；③超长正文(>4000字符)折叠「展开全文▴」。debug/release 均有出 0.1.80。

### 已实现并实测（2026-08-24~25）

1. **M5 对话页改原生 Compose**（背景：WebView 装载 DSH GUI 无法显示——GUI 工作区优先 SPA 无深链、窄视口布局塌陷、CSS module 哈希类注入无效；CDP 诊断钉死）：
   - `model/ConversationEvent.kt` + `network/DshApiClient.sessionHistory()` + `ui/ConversationViewModel.kt`
   - **流式输出**：assistant/chunk 的 text/thinking delta 聚合到"流式消息"，settled 消息替换（对齐主机实时体验）
   - 顶部 insets 让位：根 Column `statusBarsPadding+navigationBarsPadding`（Android 15 edge-to-edge 强制），顶条标题 y=52→180
   - 设计文档 §5.5 已修订
2. **语音输入**：🎤 按钮 + `android.speech.SpeechRecognizer` + RECORD_AUDIO 运行时权限（授权后**自动启动**识别，修复了"需再点一次"bug）+ `isRecognitionAvailable` 清晰降级。实测模拟器（google_apis 镜像有识别服务但无真实 Google 后端→报 ONLINE_NO_PROGRESS→优雅 Toast 降级、无崩溃）；真机可识别。
3. **APK 版本自动递增**：`app/build.gradle.kts` 每次 assemble `versionCode+1`/`versionName` 补丁位+1，持久化 `app/version.properties`（git忽略）；设置页改读 BuildConfig 动态版本。已实测 versionCode 递增。
4. **归档会话隐藏（需求 2.9.3）**：状态机追踪 `archivedSessionIds`（workspace.list + host/archived-sessions-changed 注入），快照排除归档 + 通知跳过。**实测列表"等你输入"46→2（46 个归档会话隐藏）**。
5. **工作区过滤（需求 2.9.1）+ 可折叠条（2.9.2）**：列表页工作区 chips（全部/各工作区，按 cwd↔path 过滤）+「工作区 ▾/▴」可折叠条（折叠不占空间避免干扰）。
6. **通知声音+震动**：提醒通道显式 setSound+enableVibration+pattern，更新时先删旧通道再重建（否则 install -r 新配置不生效）+ VIBRATE 权限。实测回合完成通知 `isNoisy=true`+mSound 已配置（真机必响；模拟器 -no-audio 无声属环境限制）。
7. **截图方法修复**：PowerShell `>` 把 PNG 写成 UTF-16 损坏 → 改 `cmd /c` 字节重定向 + `tools/capture_screenshot.ps1`（截图→验 PNG magic→失败自动重试）。

### 本轮批次（2026-08-25，commit 5f5e950）

- **排队消息控制**：会话页「排队消息」区逐条 ✎ 编辑 / 🗑 删除 / ⚡插话发送（`DshApiClient.sessionUpdateQueue` + `queueEditAction`/`queueRemoveAction` + `sessionSteer`）。
- **session 统计栏**：底部常驻条「14 轮·729 步 | LLM 121m58s·工具调用 51m49s | 首 token 平均 3.4s·104 tok/s | 缓存命中 99% | 输入 299M tok」+ 上下文已用 xx%（`SessionStats` 投影 + `TokenStatsLine`）。**已实机截图验证**（`docs/screenshots/conversation_statsbar.png`）。
- **主题皮肤**：默认 / 护眼蓝 / 护眼绿三套（`Theme.kt` AppThemes.DEFAULT/EYE_BLUE/EYE_GREEN + SettingsScreen 选择器 + SettingsStore.themeKey + MainActivity collect 包 DshTheme）。**已编译，未逐个截图验证**。
- **模型选择器**：对话页顶条 ModelChip ◆ → 底部弹层。**2026-08-25 重做**：分「模型」区（按供应商分组）+ 独立「思考等级」区（当前模型支持的 Off/Low/High/Max 等，无则提示）。模仿 WebUI 模型/思考等级分开选。**已实机 UI 树验证两区独立呈现**（截图 `docs/screenshots/model_picker_split.png`）。
- **消息复制 / 时间戳 / Markdown**：MessageBubble 包 `SelectionContainer`（长按复制）+ 每条消息 `MM-dd HH:mm` 时间戳 + 轻量自定义 MarkdownText 渲染器（标题/加粗/行内代码/代码块/列表）。
- **release 发布版 + APK 改名**：`assembleRelease` R8 混淆 + release 签名密钥库；`renameApks` 任务产出 `DSH-Mobile-<variant>-v<version>.apk`（debug 9.8MB / release 1.87MB，均生成）。
- **鲸鱼启动图标（改 PNG 位图）**：用官方 `/favicon.svg` 经 Node+sharp 栅格化为**蓝渐变圆角徽章 + 白色鲸鱼** PNG，按密度生成 `mipmap-*/ic_launcher.png`（`tools/make-icon.cjs` + `tools/favicon-official.svg`），替换自适应矢量方案（矢量在启动器上渲染不对）。**像素抽样验证结构正确**（角落透明/背景蓝渐变/中央白鲸）。预览图 `docs/screenshots/whale_icon_preview.png`（可打开人工确认）。
- **以 release 为主安装包**：发布版 `app-release.apk` / `DSH-Mobile-release-v<ver>.apk`（R8 混淆 + 正式签名 CN=<publisher>）为默认交付；debug 仅用于模拟器 UI 验证（release 在模拟器无法连 Host（无 debug Host 改写/cleartext），真机走 https 可连）。
- **WebUI 图片显示（主机端，2026-08-25 验证通过）**：走 `/ws` 同源插件路由（harness 插件随 `dsh web` 启动，`~/.dsh/profiles/web/ws-files-plugin.mjs`，挂 `cordis.patch.yml`），URL 形如 `http://127.0.0.1:3080/ws/<工作区相对路径>/<文件名>`，WebUI markdown 能渲染 `![alt](http://…)`。**已实机确认图片显示**（`/ws/dsh-mobile-app/docs/screenshots/whale_icon_preview.png`，探测返回 200+image/png）。**App 端原生 markdown 图片未实测**（模拟器 release 连不上 Host、debug 需卸载重装且 baseUrl 须配 `10.0.2.2`；真机需用真机可达 URL，如 tailnet IP/域名）。
- **Markdown 渲染接入封装库 jeziellago（0.1.85 提交 6f67aac）**：替换自实现渲染器为 `com.github.jeziellago:compose-markdown:0.7.2`（JitPack 仓库已加 `settings.gradle.kts`），渲染 `dev.jeziellago.compose.markdowntext.MarkdownText`。**双库实测对比**：jeziellago 表格/标题/加粗/行内代码/列表/代码块/引用全达标；mikepenz 0.23/0.30 表格仍显示成 `|` 文本 → **选定 jeziellago**。截图 `md_user_bubble.png`（成格）、`mk_user_bubble.png`/`mk030_table.png`（mikepenz 文本）。
- **对话实时滚动（0.1.88 提交 d31ac00）**：流式期间 `messages.size` 不变导致不滚动；修复=滚动触发键加 `lastKey`+`lastTextLen`（最后一条消息 key+文本长度，流式增长会触发）+ **接近底部才** `animateScrollToItem(size-1)`（避免用户上翻被拉回）+ 修正首滚索引为 size-1。模拟器实测进长流式会话滚到最新内容。
- **README 重写为完整小白指南（提交 5d907b0）+ USAGE 对齐**：README 105→169 行，新增主机端 Tailscale+serve 配置/App 安装/首次使用 3 件事/日常/排障 Q&A/构建；USAGE APK 路径统一为绝对路径。明确「只装 release，别装 debug」。
- **会话操作方案（design §5.4.1，提交 d8f46f0）**：重命名/分叉/归档三个 HTTP RPC 直调（`session.rename`/`session.fork`/`workspace.archiveSession`）；确认 `commands.execute` 在 `/api` 下 404、prompt 不识别 slash 命令 → 命令+/权限选择本期不做。

### 环境事实 / 注意事项

- 模拟器 AVD `dsh_test`（API 35 / WHPX / 4GB，`-no-audio` 故无声无震动，仅作 UI/逻辑验证），地址 `http://10.0.2.2:3080`（IDE debug Host 改写 + cleartext debug 配置 NAT 直连宿主 DSH）。
- 测试会话 `zai-coding-cn/glm-5.3` 处于 **RATE_LIMIT**（429，限额 2026-08-25 21:05 重置）→ agent 不调工具/审批难触发根因；测试应用 `deepseek-v4-flash`（主模型，额度正常）。
- 真机 <phone-model> 未连 adb（真机走 `https://<PC-name>.tailnet.ts.net`，无需 debug 配置；通知/语音/震动在真机验证最佳）。
- 一切构建从 `D:\dshm`（ASCII 联接）：`$env:JAVA_HOME="D:\dshm\.toolchain\jdk17"; $env:ANDROID_HOME="D:\dshm\.toolchain\android-sdk"; & "D:\dshm\.toolchain\gradle-8.9\bin\gradle.bat" -p "D:\dshm" --no-daemon <task>`

### 待办 / 可选深水区（需求评级中，需你拍板）
- [x] **消息可复制已做（SelectionContainer）**：消息文本可长按选中复制
- [x] **消息时间戳已做**：每条消息（用户/助手/工具）显示 MM-dd HH:mm 提交时间
- [x] **Markdown 渲染已做**：接入封装库 jeziellago/compose-markdown 0.7.2（标题/加粗/行内代码/代码块/列表/**表格**/图片），替代自实现渲染器（双库实测选定，见断点）。
- [x] **session 统计栏已做**：底部「14 轮·729 步 | LLM 121m58s·工具 51m49s | 首 token 平均 3.4s·104 tok/s | 缓存命中 99% | 输入 299M tok」+ 上下文已用 xx%（缓存命中 % 已 clamp 0-100 修 -25 抖动；输入为窗口近似非全会话累计）
- [x] **排队消息控制已做**：编辑排队消息(✎) / 删除排队消息(🗑) / ⚡插话发送（sessionUpdateQueue + sessionSteer + queueEditAction/queueRemoveAction）
- [x] **主题皮肤已做**：默认 / 护眼蓝 / 护眼绿（`Theme.kt` AppThemes + SettingsScreen 选择器 + SettingsStore.themeKey，MainActivity collect）
- [x] **模型选择器重做（分开选）**：弹层分「模型」区（供应商分组）+ 独立「思考等级」区。**已实机 UI 树验证**（截图 `docs/screenshots/model_picker_split.png`）。
- [x] **release 发布版已做**：`assembleRelease` 产出 R8 混淆签名版（R8 压缩），见下文
- [x] **代码语法高亮已做**：Markdown 代码块等宽渲染，注释/字符串/数字/关键字着色（`CodeBlock`+`highlightCode`）。**编译通过；颜色为视觉属性，需人工目检**（截图 `docs/screenshots/conversation_tool_cards.png`）。
- [x] **工具卡片富渲染已做**：助理工具调用卡片化（🔧工具名+参数）+ 工具结果卡片状态化（成功/失败）。**已实机 UI 结构验证**。
- [x] **当前会话任务列表栏已做**（todo/write 事件）：`任务 / N 进行中 · M 待处理 / 项目列表（进行中带转圈）`，可展开/收起。**已实机验证**（截图 `docs/screenshots/conversation_todo_bar.png`）。
- [x] **内联审批/提问卡已做**：挂起审批卡（工具名+原因+允许一次/拒绝→respondApproval）+ 内联提问卡；状态机快照→DshRepository→会话页。**已编译；需真实审批事件才能端到端触发验证**（测试模型 glm-5.3 RATE_LIMIT，难触发）。
- [ ] 代码语法高亮颜色、内联审批/提问卡端到端 —— 需人工/真机验收（模拟器无法验证视觉与真实审批）
- [ ] **真机 <phone-model> 验收**（通知铃声/震动/语音识别/息屏可用性——模拟器无法验证的项）
- [x] **鲸鱼图标改官方资产**：前景=官方 `/favicon.svg` 鲸鱼路径；背景=官方蓝渐变；group 平移缩放置中安全区。**已编译无崩溃；外观待用户目检**。
- [x] **会话操作已做（重命名/分叉/归档，§5.4.1）**：SessionRow 行尾 ⋮ 菜单 + 确认/输入对话框；DshApiClient+sessionRename/sessionFork/workspaceArchiveSession；修复操作后列表不即时刷新（operationTick+requestManualRefresh），分叉自动跳转。**模拟器实测通过**（release 0.1.93）。
- [ ] **会话操作真机验收** / **内联审批·提问卡端到端** / **代码语法高亮颜色目检** —— 需真机/真实事件触发（模拟器无法验证视觉与真实审批）。
- [ ] **真机 <phone-model> 验收**（通知铃声/震动/语音识别/息屏可用性——模拟器无法验证的项）。

### release 发布版（2026-08-25）
- 位置：`<repo>\app\build\outputs\apk\release\DSH-Mobile-release-v<版本>.apk`（**约 2.5MB**，R8 混淆压缩，debug 11.5MB 的约 1/5）。**用户只装 release 版。**
- **APK 清理**：`renameApks` 每次生成新 `DSH-Mobile-<variant>-v<version>.apk` **前先删除旧命名 APK**（仅清理本次实际构建的变体，避免误删未重构建的 release 包；防累积/防误拿旧包）
- 签名：release 密钥库 `keystore/release.jks`（密码存 `keystore/keystore.properties`，**均 git 忽略**，防密钥泄露）
- 非 debuggable；不含 debug 专属代码（cleartext/Host 改写，BuildConfig.DEBUG 内，R8 移除）
- 构建：`assembleDebug`=开发debug（仅模拟器验证）；`assembleRelease`=发布混淆签名版（日常交付用）
- 冒烟：模拟器启动无崩溃（R8 序列化类保留正确）；真机走 https 可连
- 版本自动递增：`app/version.properties`（versionCode/versionName 每次构建+1，git忽略）

<details><summary>历史（2026-08-24 原 WebView E2E，已被原生方案取代）</summary>

**M1–M6 全部完成 ✅（模拟器 E2E 五连测通过，commit 历史 11 个）→ 交付收口：仅剩真机可选复核**

- 模拟器 E2E 实测记录（2026-08-24，AVD dsh_test / API 35 / WHPX）：
  1. ✅ 连接：UI「已连接」状态条（App 经 debug Host 改写 + cleartext 配置 NAT 直连宿主 DSH）
  2. ✅ 会话列表：跑动中/等你输入/空闲三态分组 + blank/subagent 过滤 + 毫秒相对时间
  3. ✅ 回合完成通知：真实 prompt → 「回合完成，等待你的输入」通知（dumpsys 实证）
  4. ✅ 审批接收器回路：同 uid 广播（=通知按钮 PendingIntent 路径）→ extras → respond
     → NotPending 回执语义（logcat 实证 `approval respond -> NotPending`）
  5. ✅ 对话页 WebView：CDP 实证 title="DeepSeek Harness" url=http://10.0.2.2:3080/
  **2026-08-24 16:3x 回归复测（全新安装清状态）：五项全过，无回归**
  截图存档：`docs/screenshots_session_list.png`、`docs/screenshots_conversation.png`
- 模拟器实测排障记录（本段价值沉淀）：
  - adb reverse 在本环境数据通路坏死（监听 tcp6 但不转发）→ 弃用
  - Android 9+ cleartext 默认禁明文 → debug 源集 networkSecurityConfig 放开（release 不受影响）
  - DSH 信任栏栅（源码钉死）：Host∈{loopback 主机名|受信权威} 且无 Origin 即放行；
    10.0.2.2 非 loopback → debug 构建 Host 改写拦截器（仅 BuildConfig.DEBUG）
  - shell uid 广播无法送达 exported=false 接收器 → run-as 以 App uid 发（--user 0）
- 真机（<phone-model>）复核仍可选：adb devices 当前为空；真机走 https://<PC-name>.tailnet.ts.net

</details>
  无需任何 debug 配置（明文/Host改写均不生效于 release 逻辑）
- git：main 12 提交（含模拟器打通 a2c3cfd、终审修复 a4497e7 等）
- 一切构建从 `D:\dshm`：`$env:JAVA_HOME="D:\dshm\.toolchain\jdk17"; $env:ANDROID_HOME="D:\dshm\.toolchain\android-sdk"; & "D:\dshm\.toolchain\gradle-8.9\bin\gradle.bat" -p "D:\dshm" --no-daemon <task>`
- APK：`app\build\outputs\apk\debug\app-debug.apk`；使用说明：`USAGE.md`

## 本轮已解决的两个装机障碍（供追溯）

1. **JDK 源 404**：清华 Adoptium 目录实际文件名是 `17.0.20_8`（脚本里写的 `17.0.12_7` 已不存在）
   → 已改 URL，实测 3MB/s 下载成功（182MB/17s）
2. **脚本不可恢复重跑（v5 修复）**：`Load-State` 返回 PSObject 无法当哈希表索引，
   状态文件一旦存在，恢复运行必在首个 `Set-State` 崩溃 → 已改为把 PSObject 展开成真哈希表
   （tools/setup_build_env.ps1 L19-36）

## 已完成（本段）

---

## M0 设计与评审 ✅（基本完成）

- [x] 需求确认（8 项，用户拍板：审批按钮做/单机/中文）
- [x] DSH 源码调研（协议信封/双 WS/信任栏栅/事件帧/工作区机制，全部源码级证据）
- [x] DESIGN.md v1.0 落盘
- [x] 对抗评审 workflow（1×P0 + 4×P1 + 12×P2，仲裁完整清单）
- [x] v1.1 修正落实（17 项全部落入正文，附 §12 对照表）
- [x] 修正复审（双核对员：15 项完整、2 项瑕疵 → fix-then-pass）
- [x] 复审修复落实：§5.2 baseline() 补 origin!="subagent" 过滤；§3.3 运行时长措辞改"翻转为 true 的时刻"
- [ ] 复审通过终验（下轮核对员确认 2 处修复无新矛盾；快速项）

## M1 构建链装机 ✅（2026-08-24 08:45 完成）

- [x] `tools/setup_build_env.ps1`（v5：curl -sS + EAP 隔离 + setup_state.json + PSObject→哈希表恢复修复）
  - [!] v1：命令内再起 `pwsh` 不存在 → 改 `powershell` 直调（已解决）
  - [!] v2：脚本含中文被 GBK 误解析 → 全 ASCII（已解决）
  - [!] v3：curl 进度表 stderr 触发 EAP 终止 → `-sS` + Invoke-Native（已解决）
  - [!] v4：JDK 两源 GET 下载失败 → 实为清华文件名过期（17.0.12_7 → 17.0.20_8），已修（已解决）
  - [!] v5：**状态文件存在时恢复运行必崩**（PSObject 不能当哈希表索引）→ Load-State 展开为哈希表（已解决）
  - [x] 状态记录已生效：`.toolchain\setup_state.json`（`all: done`）
- [x] JDK17 → `.toolchain\jdk17`（17.0.20，清华源 182MB/17s）
- [x] Android SDK（platform-tools + android-35 + build-tools;35.0.0，腾讯 clt 源 + google 包源）
- [x] Gradle 8.9（腾讯源 129.8MB）
- [x] **工程骨架全部落盘**（settings/build.gradle.kts×2 + gradle.properties + Manifest + 资源 5 件 + Kotlin 源 6 件：DshApplication/MainActivity(骨架)/ConversationActivity(骨架)/EventStreamService(specialUse骨架)/ApprovalReceiver(骨架)）
  - Manifest 已含全部权限（POST_NOTIFICATIONS/FOREGROUND_SERVICE_SPECIAL_USE 等）与 specialUse 属性声明
  - Gradle 配置：AGP 8.6.1 / Kotlin 2.0.20 / compose plugin / serialization plugin
  - 注：不用 local.properties（ISO-8859-1 读中文路径会毁），改用 JAVA_HOME/ANDROID_HOME 环境变量
- [x] `assembleDebug` 空壳 APK 编译通过（BUILD SUCCESSFUL 1m28s，35 任务；APK 9.7MB，
      aapt2 badging 验证包名/权限/资源表完好；中途加 `android.overridePathCheck=true`
      解决中文路径被 AGP 拒绝的问题）

## M2 协议层 + 单测 ✅（2026-08-24 09:1x 编码完成，单测 24/24 绿）

- [x] `model/Models.kt`（信封/帧/会话/回执反序列化；schema 对照 rpc.schema.js/events.schema.js/sessions.schema.js/workspace.schema.js 钉死）
- [x] `network/DshApiClient.kt`（call/respond/probe + sessionList/workspaceList/sessionCreate/sessionPrompt/sessionCancel/respondApproval/respondQuestion + openEventStream 工厂）
- [x] `ModelParsingTest.kt`（真实帧 JSON 全类型 + 畸形容错 + 未知帧 Unknown + 信封层 rpcId）
- [x] `DshApiClientTest.kt`（MockWebServer：请求信封形状/Ok/BizError/NetError/回执三态/连通性 2xx·3xx·断连）
- [x] 单测全绿（24/24；两次修复见文首断点记录）
- [x] M2 代码独立评审（双 Agent 对抗：A 协议正确性=修复后通过 1P1+8P2；B 健壮性=修复后通过 4P1+6P2；修复全部落实，终态 54/54 绿）

## M3 前台服务 + 三类通知 ✅（2026-08-24 编码完成，单测全绿，M6 终审统一评审）

- [x] `service/NotificationStateMachine.kt`（纯 Kotlin，时钟注入，16 例全分支单测）
- [x] `service/EventStreamService.kt`（specialUse + 双 WS + 退避重连 + 世代号 + 网络切换重连 + baseline + onTimeout 降级）
- [x] `notify/NotificationHelper.kt`（三类通知 + 审批双按钮 extras 自包含 + OnlyAlertOnce + 权限守卫）
- [x] `notify/ApprovalReceiver.kt`（goAsync 协程 + 回执三态：Accepted 撤 / NotPending 撤+toast / 失败改文案）
- [x] `data/SettingsStore.kt` + `DshRepository.kt`
- [x] 单测全绿（54/54）+ assembleDebug 通过
- [x] 修复：restartStreams 误用全关方法（关执行器后无法再开流）→ 拆分 closeEventSockets/shutdownEventStreams

## M4 列表页 + 设置页 ✅（2026-08-24，commit ebb136b）

- [x] `ui/SessionListScreen.kt` + ViewModel（三态分组/blank+subagent 隐藏/连接状态条/新建底弹/相对时间心跳）
- [x] `ui/SettingsScreen.kt`（地址/测试连接/通知开关/权限行/电池优化引导/版本）
- [x] `MainActivity.kt`（POST_NOTIFICATIONS 运行时流程 + 拒绝 Snackbar 引导 + specialUse 服务启动 + 底部导航）
- [x] `DshRepository.kt` + `data/SettingsStore.kt`（M3 已落盘）

## M5 WebView 对话页 ✅（2026-08-24，commit 3e32129；真机排版验收待 M6 联调）

- [x] `ui/ConversationActivity.kt`（原生顶条+停止按钮+排版 CSS 注入+同源跳转限制）
- [x] 停止按钮抑制窗通路：notifyLocalCancel(SharedFlow) → 服务收集 → 状态机 onLocalCancel → cancel
- [ ] 真机排版验收（单手可读可输入；需真机连接）

## M6 端到端 + 交付 ✅（2026-08-24 模拟器 E2E 全通过）

- [x] 全量单测（55/55 绿）+ assembleDebug 出 APK（9.7MB）
- [x] 代码独立评审（M2 双 Agent A/B + M3–M5 终审，全部修复后通过，P0/P1 清零）
- [x] USAGE.md 使用说明落盘
- [x] DESIGN.md §11 完成度同步（实施偏差记录在案）
- [x] 模拟器 E2E 五连测（见文首断点；截图存 docs/）
- [x] 交付收口：提交 + 本台账 + 最终报告
- [ ] 真机 <phone-model> 可选复核（无 debug 配置依赖；adb 连上即可 adb install -r）

## M5 WebView 对话页（历史清单，已并入上文 M5 段）

## M6 端到端 + 交付（历史清单，已并入上文 M6 段）

---

## 当前断点 / 下一步（新轮次从这里继续）

→ 已上移至文首「⚡ 当前断点」章节（保证恢复时第一眼看到）。

## 历史断点记录（已解决，供追溯）

- 2026-08-24 早：会话无法移入 <workspace> 工作区（DSH 硬设计：cwd 锁定归属）→ 已决策：产物收 `<repo>\`，会话留 Desktop 组
- 2026-08-24 08:2x：装机脚本三次迭代（pwsh→powershell / GBK→ASCII / curl stderr→-sS+EAP隔离），脚本逻辑已通，卡在 JDK 下载源可靠性
- 2026-08-24 08:4x：用户暂停装机排障，先固化进度台账（本文件）
