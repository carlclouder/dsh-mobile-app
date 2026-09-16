"""同步最新状态到文档：插件已随 dsh 重启生效（2026-09-16 22:29），并记录手机侧排查结论。"""
import io

# ---------- 1) 插件设计文档 ----------
doc = r"D:\AI任务\dsh-mobile-app\docs\dsh-auth-gateway-plugin.md"
text = open(doc, encoding="utf-8").read()

old_status = "> 状态：**已实现并通过隔离实例验证（v1.1.1）；主 profile 已安装，待重启 dsh 生效**（2026-09-16）"
new_status = ("> 状态：**已上线生效并验证（v1.1.1；dsh 于 2026-09-16 22:29 重启后加载）**\n"
              "> 服务器侧验证：真实域名 `https://carl-pc.taild10021.ts.net/api/session/list`（与手机同一路径）返回 **200**、首页 **200**、\n"
              "> 本机裸 Host 对照仍 401；插件启动日志 `[auth-gateway] 已开启 tailnet 免令牌放行（受信 Host: carl-pc.taild10021.ts.net, …）`。")
if old_status in text:
    text = text.replace(old_status, new_status, 1)
    print("状态行已更新")
else:
    print("状态行未匹配（可能已更新过）")

old_unverified = "### 5.3 未验证项（如实标注）\n\n- **重启主服务后的实际生效**（需重启 dsh 才能验证，见 §9 待办）。"
new_verified = ("### 5.3 重启后实测（2026-09-16 22:29，真实主服务 3080）\n\n"
                "| 验证项 | 结果 |\n|---|---|\n"
                "| 插件随 dsh 重启加载 | ✅ 日志 `[auth-gateway] 已开启 tailnet 免令牌放行（受信 Host: carl-pc.taild10021.ts.net, carl-pc.tail98fa18.ts.net…）`；`免令牌网关已就绪: 3081 → 3080` |\n"
                "| **真实域名 API**（与手机同路径，经 Tailscale Serve） | ✅ **200** |\n"
                "| 真实域名首页 | ✅ 200 |\n"
                "| 本机裸 Host 对照（无令牌） | 401（认证范围未被扩大） |\n"
                "| Tailscale Serve 指向 | ✅ `http://127.0.0.1:3080`（用户脚本自动维护，未被改动） |\n\n"
                "### 5.4 仍未验证项（如实标注）\n\n"
                "- **手机端到端**：排查时发现手机 Tailscale 处于离线状态（`tailscale status`：`v2309a android offline, last seen 54m ago`），\n"
                "  即手机不在 tailnet 内，无法访问该域名；**服务器侧全部通过，手机侧需用户恢复 Tailscale 连接后实测**。")
if old_unverified in text:
    text = text.replace(old_unverified, new_verified, 1)
    print("验证章节已更新")
else:
    print("验证章节未匹配（可能已更新过）")

old_tail = "| — | **待用户重启 dsh 生效**；旧机制（计划任务 `DSH-AuthProxy`、`start-auth-proxy.vbs/.cmd`、外部代理进程、3081 占用）已全部清理 |"
new_tail = ("| 2026-09-16 22:29 | **dsh 重启，插件上线生效**：真实域名 API/首页实测 200；旧机制（计划任务 `DSH-AuthProxy`、`start-auth-proxy.vbs/.cmd`、外部代理进程、3081 占用）已全部清理 |\n"
            "| 2026-09-16 | 手机端排查：手机 Tailscale 离线（`offline, last seen 54m ago`）→ 手机侧待恢复连接后实测；服务器侧无待办 |")
if old_tail in text:
    text = text.replace(old_tail, new_tail, 1)
    print("变更记录已更新")
else:
    print("变更记录未匹配（可能已更新过）")

with open(doc, "w", encoding="utf-8", newline="") as handle:
    handle.write(text)

# ---------- 2) PROGRESS 断点 ----------
progress = r"D:\AI任务\dsh-mobile-app\PROGRESS.md"
ptext = open(progress, encoding="utf-8").read()
old_marker = "**待用户重启 dsh 生效**。"
new_marker = ("**已于 2026-09-16 22:29 随 dsh 重启生效并验证**：插件加载日志确认放行已开启（受信 Host 含当前域名）；"
              "真实域名 API/首页实测 200、本机裸 Host 对照 401；Tailscale Serve 仍指向 3080（用户脚本自动维护，未被改动）。"
              "**手机端尚未连通**：排查发现手机 Tailscale 离线（`tailscale status` 显示 `v2309a android offline, last seen 54m ago`），"
              "服务器侧全部通过，待手机恢复 Tailscale 连接后实测。")
if old_marker in ptext:
    ptext = ptext.replace(old_marker, new_marker, 1)
    print("PROGRESS 已更新")
else:
    print("PROGRESS 标记未匹配（可能已更新过）")

with open(progress, "w", encoding="utf-8", newline="") as handle:
    handle.write(ptext)
