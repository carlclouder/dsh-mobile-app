"""生成隐藏窗口启动代理的 VBS 脚本（WScript.Shell.Run 第二参数 0 = 隐藏窗口）。

背景（2026-09-16 用户报障）：自动登录代理此前由计划任务直接跑 .cmd，
在桌面留下一个常驻 cmd 窗口；用户关掉窗口后代理进程随之退出、App 立即断连。
改用 wscript 执行本 VBS 隐藏启动 node 代理：无窗口、随登录自启、不会被误关。

VBS 文件用 GBK（ANSI/CP936）编码写入，Windows 脚本宿主按系统 ANSI 代码页解析。
"""
path = r"C:\Users\Carl\.dsh\start-auth-proxy.vbs"
node = r"C:\Program Files\nodejs\node.exe"
script = r"C:\Users\Carl\.dsh\dsh-auth-proxy.mjs"
log = r"C:\Users\Carl\.dsh\logs\dsh-webui.log"

content = (
    "' DSH 自动登录代理（隐藏窗口启动）：让裸地址无 token 也能访问 dsh WebUI\r\n"
    "' 由计划任务 DSH-AuthProxy 在用户登录时调用；关闭本文件对应窗口不会出现（0 = 隐藏）\r\n"
    'Set shell = CreateObject("WScript.Shell")\r\n'
    f'shell.Run """{node}"" ""{script}"" --port 3081 --upstream-port 3080 --log ""{log}""", 0, False\r\n'
)

with open(path, "w", encoding="gbk", newline="") as f:
    f.write(content)
print("written:", path)

# 回读校验（GBK 可解码 + 关键内容在位）
with open(path, encoding="gbk") as f:
    check = f.read()
print("verify node path:", node in check, "| hidden flag:", ", 0, False" in check)
