# 连续思考步骤默认折叠验收

日期：2026-10-22。基线：41ad5f9。版本：0.1.201（202）。分支：codex/live-thinking-preview。

## 构建与测试

全量 `testDebugUnitTest`：216 项通过，失败、错误、跳过均为 0；其中本功能新增 24 项分组与摘要文案用例。
`testDebugUnitTest + assembleDebug + assembleRelease` 同一次构建成功（BUILD SUCCESSFUL，2m14s，22:10 成品）。

产物：

| 包 | 大小 | 签名 |
| --- | --- | --- |
| `DSH-Mobile-debug-v0.1.201.apk` | 12405569 字节 | Android Debug |
| `DSH-Mobile-release-v0.1.201.apk` | 2825796 字节 | 正式签名（DN CN=Carl，SHA-256 `e9aff399…7957`） |

两个包内都检索到分组键前缀 `process-group:`（debug 在 classes7.dex、release 在 classes.dex），确认是本轮新产物而不是旧包改名。

## 实际环境

本地模拟器 emulator-5554（AVD dsh_test，Android 15，1080×2400，密度 420），安装本次 Debug 包。
用 `adb reverse tcp:3080 tcp:3080` 连接本机真实 dsh（127.0.0.1:3080），打开真实会话
「AI自主推进开发新规则」——与本轮用户截图同一会话（13:38 提问「dsh会话是否有子agent并行能力?」）。
没有构造假消息、假思考或假工具输出。

## 场景与证据

| 场景 | 证据 | 结果 |
| --- | --- | --- |
| 改动前：每步一个气泡，中间连续空壳铺满整屏 | [before-stacked-steps.png](before-stacked-steps.png) | 复现问题 |
| 改动前：另一会话尾部同样逐步铺开 | [before-session-tail.png](before-session-tail.png) | 复现问题 |
| 改动后：中间 7 步合并成一行；带正文的步骤（首步「我先核对本机实际支持情况，再回答。」、后段「源码里已有明确上限…」）保持独立气泡 | [collapsed-frame.png](collapsed-frame.png) | 通过 |
| 点按展开：组内 7 步按原顺序显示（思考行、工具调用、时间） | [expanded-frame.png](expanded-frame.png) | 通过 |
| 再点按收起：回到单行 | [toggle.gif](toggle.gif) | 通过 |
| 默认收起：新进入会话时 7 个组全部显示「点按展开」 | 打开会话后 uiautomator 实测 | 通过 |
| 组内单步展开：展开组后点其中一步的思考行，只有那一步展开（其余仍是单行） | [verify-0.1.201-inner-step.png](verify-0.1.201-inner-step.png) | 通过 |
| 最终包复验：折叠态、展开态、再收起 | [verify-0.1.201-collapsed.png](verify-0.1.201-collapsed.png)、[verify-0.1.201-expanded.png](verify-0.1.201-expanded.png) | 通过 |
| 跳到底部：上翻后按钮出现，点击抵达末尾、按钮消失 | 0.1.201 设备实测，按钮节点数 1→0 | 通过 |

`toggle.gif` 录制于 0.1.200；此后为修独立审查指出的贴底触发键与组内状态 key，重新构建了 0.1.201，
并在设备上复验了折叠、展开、组内单步展开、再收起与跳到底部（见上面三张 verify 图）。
0.1.201 只改了滚动触发键与组内渲染 key，界面外观与 0.1.200 相同，因此没有重录 GIF。

`toggle.gif`：22 帧、每帧 500ms、540×1200；第 6 帧点开展开、第 15 帧点按收起。
折叠态 6 帧（0–6）、展开态 9 帧（7–15）、收起后 6 帧（16–21），两侧稳定态均 ≥2 帧，
同态逐帧 MD5 一致。原始帧 1080×2400，采集间隔实测 0.49–0.53 秒；GIF 固定 500ms 播放。
逐帧大小、哈希与采集时间见 [capture.json](capture.json) 与 [timestamps.txt](timestamps.txt)。

## 证据范围

本轮未验证：真机、深浅色主题切换、横屏、超长组（>100 步）与三位数摘要的省略号表现、
Release 包在设备上运行。按方案实现但未做设备场景的两条口径是「独立工具结果消息打断分组」
与「回合边界不打断分组」，由单测锁定，未在真实会话里构造。
