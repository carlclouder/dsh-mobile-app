# 归档废弃会话（2026-10-10 用户选定方案 A）

## 用户报障（原话）
"你截图中的大量carl会话就是 已经废弃归档的历史会话 还是没修好"

## 排查结论（关键澄清）
界面上那些 Carl 条目**不是归档会话**，而是**没有标题的会话**——App 会用工作目录末段当标题显示，
所以 cwd = `C:\Users\Carl` 的会话在界面上显示为 "Carl"。实测数据：
- 会话总数 465、归档集合 157、过滤后可见 308
- cwd = `C:\Users\Carl` 的会话 11 个，**其中只有 2 个是归档状态，9 个从未归档** ← 用户看到的就是这 9 个
→ **归档过滤本身工作正常**（157 个归档会话确实被隐藏）；用户要的是"让这些废弃会话消失"。

## 执行（用户选定 A：真正归档）
用宿主 `workspace/archiveSession` 逐个归档 cwd = `C:\Users\Carl` 且无标题且未归档的会话：
- 目标 9 个（dry-run 清单已核对）
- 结果：**9/9 成功**，归档集合 **157 → 166**
- 工具：`tools/archive_sessions.js`（支持 `--dry-run`；归档可逆）

## 验证（模拟器 App 连真实宿主）
| 检查项 | 归档前 | 归档后 |
|---|---|---|
| 界面上 "Carl" 字样出现次数 | 6 | **0** |
| 界面上 `C:\Users\Carl` 路径出现次数 | 3 | **0** |
界面工作区与分组显示正常（dsh-mobile-app / Quantitative_investment_system 等）。

证据文件：`after_archive_carl_sessions.png`、`ui_dump_after_archive.xml`。

## 未处理（待用户决定）
另有 **10 个同类无标题"测试残留"会话**仍会显示（App 用目录末段当标题）：
`card-reuse-test` / `project`（×3）/ `project-nocard` / `test-project`（×2）/
`全局规则升级-20261002` / `AI任务` / `project`（D:\dsh-card-verify）。

## 未验证
真机（用户手机）侧效果。
