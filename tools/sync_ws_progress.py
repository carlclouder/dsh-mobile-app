"""更新 PROGRESS 的 /ws 未决项状态：全部插件嫌疑已排除，已重装 image-serve 待重启验证。"""
path = r"D:\AI任务\dsh-mobile-app\PROGRESS.md"
text = open(path, encoding="utf-8").read()

old_marker = "**未排除**：主实例自身的运行态"
index = text.find(old_marker)
if index < 0:
    raise SystemExit("锚点未找到")
end = text.find("**\n", index)
if end < 0:
    raise SystemExit("段落结尾未找到")

new_text = (
    "**补充排查（2026-10-10 继续）**：又排除两项——① 隔离实例复刻「image-serve + solution-explorer + 用户级 patch」组合，"
    "``/ws`` 正常（相对 400／绝对 200）；② 唯一未测的 ``dsh-chat-import`` 源码核查：其精确路由全部位于 "
    "``/api-import/*``（sessions/import/history/purge/prefs/sync），不拦截 ``/ws``。"
    "**至此主 profile 的全部插件与配置均已排除**，指向该插件在主 profile 中的安装状态。"
    "**已执行修复**：备份清单（``package.json.bak-20261010-重装image-serve``）后 "
    "``dsh plugin --profile web remove dsh-image-serve`` → ``add dsh-image-serve@2.2.2``（均 exit 0，"
    "bundles 已含该插件，复查无 ``dsh-file-upload`` 冲突）。**待下次重启 dsh 验证**：重启后重测 "
    "``curl http://127.0.0.1:3080/ws/README.md``（应回 400 而非 404）"
)

text = text[:index] + new_text + text[end:]
with open(path, "w", encoding="utf-8", newline="") as handle:
    handle.write(text)

check = open(path, encoding="utf-8").read()
print("已更新:", "已执行修复" in check)
