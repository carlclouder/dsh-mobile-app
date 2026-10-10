"""记录 /ws 未决项到 PROGRESS（含已排除项与下一步动作）。"""
path = r"D:\AI任务\dsh-mobile-app\PROGRESS.md"
text = open(path, encoding="utf-8").read()

marker = "**状态：工作区/归档数据源改为 WebSocket 流，修复手机报障（2026-09-16）"
index = text.find(marker)
if index < 0:
    raise SystemExit("锚点未找到")

note = (
    "**未决项：`/ws` 图片通道在主实例返回 404（2026-10-10，非用户报障，不影响功能）**——"
    "现象：`http://127.0.0.1:3080/ws/<路径>` 一律 404（连插件对相对路径应回的 400 也没有 → 路由未注册）；"
    "同机隔离实例（同 dsh 版本 + 同插件组合的最小集）实测正常（相对 400／绝对 200）。"
    "**已实测排除**：插件文件完整性与版本（2.2.2，与 tgz 清单一致）、bundles 登记、依赖写法、"
    "`dsh-image-serve` 在主 profile 内可被 node 正常 import（`LOAD OK` 且导出齐全）、"
    "`@deepseek-ai/schemastery` 可解析、用户级 patch（含 8 个 MCP 段与两处覆盖行）、"
    "三个额外插件（dshmarket／dsh-chat-import／dsh-solution-explorer 的 patch 均只 insert 自身条目、无路由前缀冲突）。"
    "**未排除**：主实例自身的运行态（实例起于 08:03，日志仅 3 行、stderr 无插件错误，证据链在此中断——不编原因）。"
    "**影响**：仅影响“会话内直接显示本地图片”；截图/证据展示已改用后备通道 `http://127.0.0.1:34567/<相对路径>`（实测 200，可用）。"
    "**下一步**：待下次 dsh 重启窗口复测；若仍 404，用隔离实例的干净组合重建该插件"
    "（`dsh plugin --profile web remove dsh-image-serve` → `add dsh-image-serve@2.2.2` → 重启）再验。**\n\n"
)
text = text[:index] + note + text[index:]
with open(path, "w", encoding="utf-8", newline="") as handle:
    handle.write(text)

check = open(path, encoding="utf-8").read()
print("已记录未决项:", "未决项：`/ws` 图片通道在主实例返回 404" in check)
