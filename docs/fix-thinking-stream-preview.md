# 思考折叠行流式滚动摘要（对齐 WebUI ReasoningRow）方案

> 状态：**方案评审通过，待实施**（2026-09-04）。实施完成后凭真实工件（代码/测试输出/截图/version/git 提交号）回填结果。

## 1. 需求

思考过程在折叠情况下，增加像 WebUI 类似的"快速单行横向滚动刷新显示最新末尾思考语句"效果，
让用户一眼判断当前是在思考（文字持续推进）还是卡住了（文字停住不动）。

## 2. WebUI 参考实现（证据链）

来源：`@deepseek-ai/dsh-client-ui-conversation/lib/client.js` 中 `ReasoningRow` 组件（反编译可读片段）：

1. **摘要内容**：`summary = running ? latestLine(text) : firstLine(text)`；
   `latestLine` = `text.trimEnd()` 后取最后一个 `\n` 之后的片段（无换行取全文）。
2. **滚动钉尾**：`element.scrollLeft = running ? element.scrollWidth - element.clientWidth : 0`，
   经 `useThrottledVisualUpdate` 节流驱动，`useEffect` 依赖 `[running, summary]`——摘要每次变化重新钉到最右。
3. **活动扫光**：`[data-state=running]` 时行内叠加 300px 渐变光带，
   `@keyframes` 2.6s ease-out 无限循环从左扫到右（`left:-300px → 100%`）；`prefers-reduced-motion` 时禁用。
4. **单行裁剪**：`.summary { white-space:nowrap; overflow:hidden; text-overflow:ellipsis }`，
   流式时 `data-follow-end` 切 `text-overflow:clip`（配合钉尾）。
5. 点整行展开全文（`thinkBody`，pre-wrap）。

**语义**：文字持续向左推进 = 正在思考；文字停住但扫光仍在 = 回合在跑但思考无新产出（可能在调工具/卡住）；
两者都停 = 回合结束。这正是需求要的"区分思考与卡住"。

## 3. App 现状与差距

`ConversationActivity.kt` 的 `CollapsibleThinking`（原 558 行）：折叠时恒显示静态灰字
「💭 思考过程 · 点按展开」，流式期间该行毫无变化——用户无法区分思考与卡住。

流式数据链路已具备：`ConversationViewModel` 把 `assistant/chunk` 的 `thinkingDelta` 按 80ms 节流
聚合进 `STREAMING_KEY` 占位消息的 `reasoning` 字段；`MessageBubble` 已能识别流式占位
（`msg.key == STREAMING_KEY`，原 377 行）。

## 4. 设计

### 4.1 交互行为（对齐 WebUI）

| 状态 | 折叠行显示 |
|---|---|
| 流式中（`STREAMING_KEY` 占位消息） | `💭 思考中 <单行摘要>`，摘要=最新一行，钉尾横向滚动 + 扫光动画（标签与摘要间以 6dp 间距分隔，未用字面「·」——已声明偏差，观感等价） |
| 已落定 / 历史消息 | 保持现状：「💭 思考过程 · 点按展开」（不改历史观感，控制改动面） |
| 点按折叠行 | 展开全文（现有行为保留，流式中也可展开看全文） |

落定后占位消息被 settled 消息替换，折叠行自然回退到静态文案（对齐 WebUI running→ok 切换）。

### 4.2 新增文件 `ui/ThinkingStreamRow.kt`

`ConversationActivity.kt` 已 1185 行（超 800 行规范、拆分已在挂账），新逻辑全部放新文件：

```kotlin
// —— 纯函数（JVM 单测覆盖）——

/** 取思考文本的最新一行：trimEnd 后取最后一个 '\n' 之后的片段（逐字对齐 WebUI latestLine） */
internal fun thinkingLatestLine(content: String): String {
    val visible = content.trimEnd()
    val newline = visible.lastIndexOf('\n')
    return if (newline == -1) visible else visible.substring(newline + 1)
}

/** 扫光光带宽度（px）：行宽 × 0.35，夹在 [60dp, 160dp] 换算像素之间（宽度随行自适应，不写死；dp→px 用屏幕密度 density，非字体缩放 fontScale） */
internal fun thinkingSweepBandWidthPx(rowWidthPx: Float, density: Float): Float

// —— Composable ——

/** 流式思考折叠行：💭 思考中 + 单行钉尾滚动摘要 + 扫光动画；点按回调交外部展开 */
@Composable
internal fun ThinkingStreamRow(content: String, onExpand: () -> Unit, modifier: Modifier = Modifier)
```

### 4.3 钉尾滚动实现（伪码）

```
Text(summary) 包在 horizontalScroll(scrollState, enabled=false) 里
  （enabled=false：对齐 WebUI overflow:hidden，不响应手势，避免与钉尾打架）

LaunchedEffect(scrollState):
    snapshotFlow { scrollState.maxValue }.collect { maxValue ->
        scrollState.scrollTo(maxValue)     // maxValue 随文本变长在布局后更新，每次更新钉到最右
    }
```

- 单层机制（评审 P2-7 采纳简化）：maxValue 是随布局更新的可观察状态，摘要变长 → 文本变宽 →
  maxValue 更新 → collect 触发钉尾，无需按 summary 重启 effect；effect 挂 scrollState 常驻不重启。
- `scrollTo` 为同步瞬移（对齐 WebUI 直接赋 scrollLeft，无补间），80ms 步进（VM 节流）在人眼下即连续滚动。

### 4.4 扫光动画（对齐 WebUI 2.6s 光带）

```
rememberInfiniteTransition: progress 0→1，tween(2600ms, LinearEasing)，无限循环
Modifier.drawWithContent（DrawPhase 读取动画值，只重绘不重组）:
    drawContent()
    bandWidth = thinkingSweepBandWidthPx(size.width, density)
    x = (size.width + bandWidth) * progress - bandWidth      // 从左缘外扫到右缘外
    drawRect(Brush.horizontalGradient(透明→surface.copy(alpha=0.6f)→透明), topLeft=(x,0))
行容器 clipToBounds() 裁剪出界部分
```

- 尊重系统"减少动画"：`Settings.Global.ANIMATOR_DURATION_SCALE == 0f` 时不播扫光（对齐 WebUI
  prefers-reduced-motion），仅保留钉尾滚动。读取时机=组合时一次；用户中途改系统动画开关需重进
  会话才生效（低频场景，不做广播监听，注释说明）。
- 与 WebUI 的已声明偏差：WebUI 光带 easing 为 ease-out（前快后慢），本方案用 LinearEasing
  （循环重启时刻速度更均匀）——仅缓动曲线差异，时长与方向一致。
- 动画值仅在绘制阶段读取，不触发行重组；且只有当前流式行挂动画，历史行零开销（视口外 item
  整体移出组合，动画与协程一并销毁）。

### 4.5 边界情况

| 场景 | 行为 |
|---|---|
| reasoning 为空/纯空白 | 上游 `takeIf { it.isNotBlank() }` 已挡，不渲染行 |
| 单行短摘要（未超宽） | maxValue=0，scrollTo(0) 无操作；扫光照常提示"活动中" |
| 末尾连续换行 | trimEnd 先吃掉，返回最后一段非空文本（与 WebUI 一致） |
| CRLF（`"a\r\nb"`） | lastIndexOf('\n') 命中 `\n`（位于 `\r` 之后），substring 取其后 `"b"`；`\r` 属于上一行行尾自然不进摘要，行为与 WebUI 一致。另：孤立 `\r` 与 `\n` 分属两次 flush 时，trimEnd 会短暂吃掉 `\r` 使摘要回退上一段，下次 flush 自愈（瞬态，可接受） |
| trimEnd 字符集微差 | JS trimEnd 剥 U+00A0 等不换行空格、Kotlin `Char.isWhitespace()` 不剥——思考文本以此类字符结尾概率极低，无视觉/功能差异，不做特殊处理 |
| 流式中展开 | 显示全文（当时已收到的 reasoning）；落定后仍保持展开。⚠️ 该行为**依赖 LazyColumn 无 key 索引复用**（165 行注释：不传 key 防崩溃）——将来若给 items 加 key，落定会回折（降级后果轻微，仅折叠回去，功能无损） |
| 历史消息异步回填与流式并发 | 历史批次插入前部时占位消息 index 后移，无 key 列表的 remember 状态可能瞬态串位（钉尾初始位置错乱）——下一次 80ms flush 即自愈；expanded 串位为现状已有同类风险，非本方案新引入 |
| 用户上翻离开底部 | 思考行随消息列表滚出视口，item 整体移出组合，动画/协程随之销毁 |

### 4.6 改动清单

1. 新增 `app/src/main/java/dev/dshmobile/ui/ThinkingStreamRow.kt`：纯函数 ×2 + `ThinkingStreamRow`。
2. `ConversationActivity.kt`：
   - `MessageBubble` 调用处改 `CollapsibleThinking(it, streaming = msg.key == STREAMING_KEY)`；
   - `CollapsibleThinking` 增加 `streaming` 参数：折叠+流式 → `ThinkingStreamRow(content, onExpand = { expanded = true })`，其余走现有渲染。
3. 新增 `app/src/test/java/dev/dshmobile/ThinkingStreamLineTest.kt`（跟随项目既有测试扁平目录惯例，
   package `dev.dshmobile` 跨包引用 internal 可见）：latestLine 7 例 + 扫光宽度 4 例（含 0 宽兜底）。

## 5. 测试计划

1. **JVM 单测**（Kotlin，语言一致）：latestLine 单行/多行/尾部换行/空串/前后空白/CRLF/长文本；
   扫光宽度 clamp 边界（0.35 比例命中区间 / 低于下限 / 高于上限 / 0 宽兜底），期望值按 density 手算对照。
   ⚠️ 测试必须从 ASCII 联接路径 `D:\dshm` 跑（项目铁律）；中文原路径下 Gradle 测试进程类加载全红
   （评审 P1-E 实证，含历史测试类，与代码无关）。
2. **编译**：`assembleDebug` + `assembleRelease` 0 错。
3. **模拟器 E2E**（AVD dsh_test，debug 包连 10.0.2.2:3080 宿主）：发消息触发带思考的流式回复，
   验证 ①折叠行显示「💭 思考中」+ 摘要文字持续推进（两张间隔截图文字不同且都贴尾）；
   ②落定后回退「💭 思考过程 · 点按展开」；③点行展开全文正常。
   截图存 `docs/screenshots/`。
4. 真机行为（触摸滚动观感、性能）未验证，需真机复核——明说不猜。

## 6. 实施结果（2026-09-04 实施完成后凭真实工件回填）

- **评审**：方案两轮评审（首审不通过→修正 P0 文档虚构/P1 density 参数→复审通过）；代码评审通过
  （0 P0 / 0 代码缺陷 P1；2 项 P2 文档同步已落实）。评审员报告的 P1-E"测试全红"实为在中文原路径
  跑测试所致——项目铁律从 `D:\dshm` ASCII 联接路径构建/测试，标准路径下全绿，非代码问题。
- **单测**：`testDebugUnitTest` 全量 **101/101 绿**（历史 90 + 新增 ThinkingStreamLineTest 11：
  latestLine 7 例 + 扫光宽度 clamp 4 例，期望值按 density 手算）。
- **编译**：`assembleDebug` + `assembleRelease` 0 错；产物 **v0.1.134（versionCode 135）**：
  `app\build\outputs\apk\release\DSH-Mobile-release-v0.1.134.apk`（2.5MB）/ debug 同批。
- **模拟器 E2E**（AVD dsh_test，debug 包连宿主 glm-5.3 真实流式）：
  ① 流式折叠行「💭 思考中」+ 末尾摘要钉尾：思考增长期摘要=`4567: 4+5+6+7 = 22 → 2+2 = `，
  思考结束后摘要=思考最终末尾 `…rt-ish but show the check…`——两帧文字不同且都贴尾，钉尾滚动刷新实证；
  ② 扫光光带清晰可见（s06 摘要 "the check, consistent" 上的浅色渐变带）；
  ③ 落定后回退「💭 思考过程 · 点按展开」静态文案（17×23 与 4567×8910 两轮均验证）。
  截图：`docs/screenshots/thinking_stream_e2e_1.png`（思考增长期）、`thinking_stream_e2e_2.png`
  （扫光+贴尾）、`thinking_stream_e2e_settled.png`（落定回退）、`thinking_stream_e2e_expanded.png`。
- **未验证项（明说不猜）**：流式中点按展开全文未抓到实测帧（消息落定快于点击），代码路径与落定展开
  共用同一 expanded 逻辑；真机触摸观感/性能未测，需真机复核。
- **提交**：`feat: 思考折叠行流式滚动摘要（对齐 WebUI ReasoningRow）`。
