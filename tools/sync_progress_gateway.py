"""同步 PROGRESS.md 断点：宿主机免令牌方案定稿为认证链放行（文件中转，避免命令行转义破坏内容）。"""
path = r"D:\AI任务\dsh-mobile-app\PROGRESS.md"
text = open(path, encoding="utf-8").read()

marker = "**状态：宿主机改「免令牌网关插件」方案，待重启生效（2026-09-16）"
start = text.find(marker)
if start < 0:
    raise SystemExit("未找到待替换的断点段落")

end = text.find("\n\n", start)
if end < 0:
    raise SystemExit("未找到段落结束位置")

new_paragraph = (
    "**状态：宿主机「免令牌网关」以认证链放行定稿，待重启生效（2026-09-16）"
    "——用户四条硬要求（逐字）：①\u201c他应该是一个无感 伴随dsh启动跟随启动的东西\u201d；"
    "②\u201c而不是独立维护的\u201d；③\u201cdsh有很多 管理bat…完全无法配套管理\u201d；④\u201c不要动已有bat\u201d。"
    "方案演进：外部代理进程+计划任务（否决：独立维护、可能被拦、误关窗口即断连）"
    "→ 改 bat 内联（否决：与 dsh 控制脚本无关联、每次调整都要动 bat）"
    "→ **dsh 插件 dsh-auth-gateway**（采纳）。插件跑在 dsh 进程内："
    "①`connection.requestRejection` 放行 API/WS 升级；②`connection.authorizeIndex` 放行首页"
    "（实测只补 ① 时 API 200、根路径仍 401，v1.1.1 补齐）；"
    "判据 = 来源 loopback（Serve 是本机进程）+ Host 命中 `--trusted-host` 受信名单。"
    "**Tailscale Serve 保持 3080 不变，任何启动脚本都不用改**。"
    "安全含义：tailnet 内可达设备免令牌（公网仍不可达），关闭 `allowTailnetForwarded` 或卸载即恢复原生认证。"
    "隔离实例四项对照全部符合预期（Serve 风格 API/首页 200；本机裸 Host 401；Host 拼错 401）。"
    "主 profile 已装 v1.1.1（bundles 干净）；旧机制（计划任务 DSH-AuthProxy、start-auth-proxy.vbs/.cmd、"
    "外部代理进程、3081 占用）**已全部清理**。**待用户重启 dsh 生效**。"
    "文档：docs/dsh-auth-gateway-plugin.md（设计/方案演进/验证/运维/回滚）、docs/DESIGN.md §13、"
    "docs/ARCHITECTURE.md、USAGE.md（地址填法）。"
    "新坑已固化：plugin add 会把 dsh-file-upload 带回 bundles，每次装完必须复查移除。**"
)

text = text[:start] + new_paragraph + text[end:]
with open(path, "w", encoding="utf-8", newline="") as handle:
    handle.write(text)

check = open(path, encoding="utf-8").read()
print("updated:", new_paragraph[:40] in check)
print("旧段落已移除:", marker not in check)
