"""写代理启动包装脚本（.cmd 必须 GBK 编码——Windows cmd.exe 只认 ANSI/CP936）。"""
path = r"C:\Users\Carl\.dsh\start-auth-proxy.cmd"
content = (
    "@echo off\r\n"
    'rem DSH 自动登录代理：让裸地址（无 token）经代理自动带会话 Cookie 访问 dsh\r\n'
    '"C:\\Program Files\\nodejs\\node.exe" "C:\\Users\\Carl\\.dsh\\dsh-auth-proxy.mjs" '
    "--port 3081 --upstream-port 3080 --log \"C:\\Users\\Carl\\.dsh\\logs\\dsh-webui.log\"\r\n"
)
with open(path, "w", encoding="gbk", newline="") as f:
    f.write(content)
print("written:", path)
