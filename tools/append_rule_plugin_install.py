"""向全局规则追加"坑三：dsh plugin install 不补齐插件且清登记"（文件中转，避免匹配/转义问题）。"""
path = r"C:\Users\Carl\.dsh\AGENTS.md"
text = open(path, encoding="utf-8").read()

anchor = "任何配置修改前先落 `.bak-日期-说明` 备份。"
if anchor not in text:
    raise SystemExit("未找到锚点")
if "坑三：`dsh plugin install` 不补齐缺失插件" in text:
    print("规则已存在，跳过")
    raise SystemExit(0)

addition = (
    "\n   - **坑三：`dsh plugin install` 不补齐缺失插件，还会把登记一起清掉（2026-09-16 隔离环境实测）**。"
    "`dsh.profile.bundles` 记录的是**实际安装状态**而非\"期望状态\"：手动删掉 "
    "`profiles/web/node_modules/<插件>` 后再跑 `dsh plugin --profile web install`，"
    "输出 `Already up to date`、插件**未恢复**，且该插件在 `bundles` 里的登记**同时消失**。"
    "结论：①插件丢失后**唯一恢复手段是重新 `dsh plugin --profile web add <tgz>`**；"
    "②**不要手工删 `node_modules` 里的插件目录**（卸载一律走 `dsh plugin --profile web remove`）；"
    "③不要指望\"声明清单 → 自动补齐\"这种声明式恢复。参考实现："
    "`D:\\AI任务\\dsh-mobile-app\\tools\\install-auth-gateway.ps1`（幂等安装/修复脚本，含 file-upload 冲突自动清理）。"
)

index = text.find(anchor) + len(anchor)
text = text[:index] + addition + text[index:]
with open(path, "w", encoding="utf-8", newline="") as handle:
    handle.write(text)

check = open(path, encoding="utf-8").read()
print("已追加:", "坑三：`dsh plugin install` 不补齐缺失插件" in check)
