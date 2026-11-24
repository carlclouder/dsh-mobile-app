# 连续思考步骤默认折叠验收

日期：2026-10-22。基线：41ad5f9。版本：0.1.207（208）。分支：codex/live-thinking-preview。

## 构建与测试

全量 `testDebugUnitTest`：222 项通过，失败、错误、跳过均为 0；其中本功能新增 30 项分组、尾部标记与摘要文案用例。
`testDebugUnitTest + assembleDebug + assembleRelease` 同一次构建成功（BUILD SUCCESSFUL，2m23s）。

产物：

| 包 | 大小 | 签名 |
| --- | --- | --- |
| `DSH-Mobile-debug-v0.1.207.apk` | 12405569 字节 | Android Debug |
| `DSH-Mobile-release-v0.1.207.apk` | 2825796 字节 | 正式签名（DN CN=Carl，SHA-256 `e9aff399…7957`） |

## 实际环境

本地模拟器 emulator-5554（AVD dsh_test，Android 15，1080×2400，密度 420），安装本次 Debug 包。
用 `adb reverse tcp:3080 tcp:3080` 连接本机真实 dsh（127.0.0.1:3080），打开真实会话：
「AI自主推进开发新规则」——与本轮用户截图同一会话（13:38 提问「dsh会话是否有子agent并行能力?」）；
尾部折叠行在另一条真实会话「dsh」（10-18，末尾 2 个无正文步骤）上验证。没有构造假消息或假思考。

## 场景与证据

| 场景 | 证据 | 结果 |
| --- | --- | --- |
| 改动前：每步一个气泡，中间连续空壳铺满整屏 | [before-stacked-steps.png](before-stacked-steps.png) | 复现问题 |
| 改动前：另一会话尾部同样逐步铺开 | [before-session-tail.png](before-session-tail.png) | 复现问题 |
| 默认折叠：连续 7 步收成一行，行首 `▸` 折叠标识，写明步数、工具调用数与「点按展开」 | collapsed-frame.png（已停止公开） | 通过 |
| 手动点开：**还原成原来一个个独立气泡**（各自气泡边框、自己的「思考过程 · 点按展开」、工具调用、时间行），不是塞进同一个框 | expanded-frame.png（已停止公开）、[expanded-separate-bubbles.png](expanded-separate-bubbles.png) | 通过 |
| 再点收起：回到单行 | toggle.gif（已停止公开） | 通过 |
| 带正文的步骤不折叠：同一会话首步「我先核对本机实际支持情况，再回答。」与后段带正文步骤仍是独立气泡 | collapsed-frame.png、tail-row.png | 通过 |
| 单条无正文不折叠：只有一步时保持原来的独立气泡 | 源码与单测口径；设备上「dsh」会话末条单步仍为独立气泡 | 通过 |
| 列表尾部组：收起时只滚动显示最新一条内容（`▸ 💭 2 步` + 钉尾滚动），点开还原独立气泡 | [tail-row.png](tail-row.png)、[ui-tail-row.xml](ui-tail-row.xml) | 通过 |
| 分组键稳定：后续步骤并入同组时展开状态不丢 | 单测 `group key stays stable when the run grows` | 通过（单测） |
| 跳到底部按渲染项数定位 | 0.1.207 设备实测，按钮出现后点击抵达末尾、按钮消失 | 通过 |

`toggle.gif`（已停止公开）：22 帧、每帧 500ms、540×1200；第 6 帧点开展开、第 15 帧点按收起。
折叠态 7 帧（0–6）、展开态 9 帧（7–15）、收起后 6 帧（16–21），两侧稳定态均 ≥2 帧，
同态逐帧 MD5 一致。原始帧 1080×2400，采集间隔实测 0.49–0.53 秒；GIF 固定 500ms 播放。
逐帧大小、哈希与采集时间见 [capture.json](capture.json) 与 [timestamps.txt](timestamps.txt)。

## 证据范围

本轮未验证：真机、深浅色主题切换、横屏、超长组（>100 步）与三位数摘要的省略号表现、
Release 包在设备上运行。分组口径里的「独立工具结果消息打断分组」「回合边界不打断分组」
只有单测覆盖，未在真实会话里构造。
