# 归档全部无标题测试残留会话（2026-10-10，用户复核截图后追加）

## 用户指令（原话）
"截图请复核，大量projects会话 明显是历史会话"

## 执行
`tools/archive_sessions.js --all-untitled`：归档【全部无标题且未归档】的会话（App 用目录末段当标题，
这些条目在界面上显示为 project / card-reuse-test / test-project / 全局规则升级-20261002 / AI任务 等）。

- dry-run 清单 17 个（全部为测试残留目录：`D:\dsh-card-verify\*`、`D:\Documents\Desktop\全局规则升级-20261002\*`、`D:\AI任务`）
- 结果：**17/17 成功**，归档集合 **166 → 183**（本轮累计 157 → 183，共归档 26 个）

## 验证（模拟器 App 连真实宿主，界面关键词计数）
| 关键词 | 归档前 | 归档后 |
|---|---|---|
| project | 4 | 0 |
| card-reuse-test | 2 | 0 |
| 全局规则升级 | 1 | 0 |
| dsh-card-verify | 2 | 0 |
| card-verify | 2 | 0 |

证据文件：`after_archive_all_test_sessions.png`、`ui_dump_after_archive_all.xml`。

## 说明
- 归档可逆（宿主支持取消归档）；工具支持 `--dry-run` 预演。
- 工具用法：`node tools/archive_sessions.js [--all-untitled] [--dry-run]`
  - 不带参数：只归档 cwd = `C:\Users\Carl` 的无标题会话
  - `--all-untitled`：归档全部无标题会话
