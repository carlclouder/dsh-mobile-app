# 思考折叠行流式滚动摘要 — 场景测试证据归档（2026-09-04）

> 需求原话（逐字）："思考过程在折叠情况下应该增加像webui类似的快速单行横向滚动刷新显示最新末尾思考语句效果。让我知道当前是在思考还是卡住了"
>
> 环境：AVD dsh_test（API 35），DSH-Mobile-debug-v0.1.134（dfdea9b 构建），宿主 DSH 127.0.0.1:3080，模型 cn/glm-5.3（真实流式，含 web_search 工具调用回合）。
> 场景消息："Plan a 3 days trip to Chengdu with budget breakdown, then compute 7891*2345 and verify it. Be detailed."（触发多段长思考 + 工具调用）

## 需求场景 → 场景测试对照清单

| # | 场景（源自需求） | 测试方法 | 断言 | 结果 | 截图证据 |
|---|---|---|---|---|---|
| S1 | 折叠态单行显示最新**末尾**思考语句 | 流式思考进行中连续截帧（间隔约 2.4s） | 摘要=思考文本最后一段；随思考推进持续更新；始终贴尾 | **通过**：三帧摘要依次为 `9×2345=21,105…`(s5 帧) → `prices per those source pages) and note to verify on`(frame_2) → `es. Cite the ticket price sources with markdown links.`(frame_5)，均贴尾 | s1_thinking_row_before_tap.png、s1_roll_frame_2.png、s1_roll_frame_5.png；另有 docs/screenshots/thinking_stream_e2e_1/2.png（上一轮会话独立复测） |
| S1b | "快速横向滚动刷新"的钉尾机制 | 摘要超宽时帧序列对比（同上） | 超宽摘要只显示末尾（开头被滚出）= scrollLeft 钉在最右 | **通过**：frame_2/frame_5 摘要均超屏宽且只见末段 | 同上 |
| S2 | 活动扫光（区分"回合在跑"） | 流式帧目检 | 行上有渐变光带扫过 | **通过**：frame_2 "those source"、frame_5 "markdown links" 处浅色渐变带清晰可见 | 同上 |
| S3 | 思考落定 → 回退「💭 思考过程 · 点按展开」 | 回合结束后/重进会话目检 | 流式行消失，回静态文案 | **通过**：17×23 回合落定（docs/screenshots/thinking_stream_e2e_settled.png）+ 本回合第一段思考落定（s1_roll_frame_2.png 中部「💭 思考过程·点按展开」+ web_search 工具卡） | docs/screenshots/thinking_stream_e2e_settled.png、s1_roll_frame_2.png |
| S4 | **流式中点按折叠行 → 展开思考全文** | uiautomator 定位「💭 思考中」节点坐标 → tap → 截图 | 展开显示思考全文（当时已收到的部分），流式不中断（顶栏「停止」仍在） | **通过**：展开显示 "The user asks two things: 1. Plan a 3-day trip… 2. Compute 7891 × 2345…"，回合继续运行 | s4_expanded_during_stream.png |
| S5 | **展开后再点 → 收起回滚动摘要** | tap 展开的全文区域 → 截图 | 回到「💭 思考中 + 末尾摘要钉尾+扫光」折叠态 | **通过**：收起后摘要=`…9×2345=21,105) → 18,504,395. ✓ Nice consistency`（贴尾，右端光带） | s5_collapsed_back.png |
| S6 | **历史/落定消息不受流式组件影响** | 退出会话 → 重进，目检历史思考行 | 历史行=静态「💭 思考过程 · 点按展开」 | **通过**：重进后 4567×8910 回合的思考行为静态文案 | s6_history_reenter.png |

## "在思考 vs 卡住"判定语义（S1+S2 组合达成需求目标）

- 摘要文字持续推进（S1）= 正在思考；
- 摘要文字停住 + 扫光仍在（S2）= 回合在跑但思考无新产出（模型在调工具/等待——本回合 web_search 调用期间实测可见）；
- 扫光消失 = 回合结束（S3 回退静态）。

## 未覆盖项（明说）

- **真机**：触摸观感、扫光流畅度、息屏行为——模拟器无法代表真机，需真机复核。
- **系统"减少动画"开启**时扫光停播（代码路径有 `ANIMATOR_DURATION_SCALE==0` 判断），未在模拟器上实测该开关场景。
- 超长单行思考（>一屏宽数十倍）的滚动性能未压测（逻辑上 horizontalScroll 仅视口渲染，但未用极端文本实测帧率）。

## 运行记录

- 测试会话：`Calculating 17 Times 23`（宿主自动命名），共 5 轮·6 步，LLM 5m0s，含 1 次 web_search 工具调用。
- 证据归档：本目录 6 张截图（git 提交）；上一轮会话证据在 `docs/screenshots/thinking_stream_e2e_*.png`。
