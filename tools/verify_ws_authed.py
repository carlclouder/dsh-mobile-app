"""带认证验证 /ws 可访问性（两种认证方式：受信 Host 免令牌路径 / 令牌换 Cookie）。"""
import http.client
import json
import re
import urllib.parse

HOST_AUTH = "carl-pc.taild10021.ts.net"
PORT = 3080
FILES = ["before_workspace_empty.png", "after_workspace_restored.png", "workspace_switch.gif"]
BASE = "D:/AI任务/dsh-mobile-app/test-evidence/workspace-stream-20260916"


def get(path, headers):
    conn = http.client.HTTPConnection("127.0.0.1", PORT, timeout=10)
    conn.request("GET", path, headers=headers)
    resp = conn.getresponse()
    body = resp.read()
    conn.close()
    return resp.status, body, resp.headers.get("content-type")


print("=== 方式一：受信 Host（免令牌路径，与手机经 Serve 等价） ===")
for name in FILES:
    path = urllib.parse.quote(f"/ws/{BASE}/{name}", safe="/:")
    status, body, ctype = get(path, {"Host": HOST_AUTH})
    print(f"{name} -> HTTP {status}  {len(body)} 字节  {ctype}")

print("\n=== 方式二：令牌换 Cookie 后访问（与浏览器行为一致） ===")
log = open(r"C:\Users\Carl\.dsh\logs\dsh-webui.log", encoding="utf-8", errors="replace").read()
tokens = re.findall(r"dsh web: http://127\.0\.0\.1:\d+/\?token=([A-Za-z0-9_-]+)", log)
if not tokens:
    print("日志中无令牌，跳过")
else:
    conn = http.client.HTTPConnection("127.0.0.1", PORT, timeout=10)
    conn.request("GET", f"/?token={tokens[-1]}", headers={"Host": HOST_AUTH})
    resp = conn.getresponse()
    resp.read()
    cookie = None
    for key, value in resp.getheaders():
        if key.lower() == "set-cookie":
            cookie = value.split(";")[0]
    conn.close()
    print("cookie:", (cookie or "无")[:40])
    for name in FILES:
        path = urllib.parse.quote(f"/ws/{BASE}/{name}", safe="/:")
        status, body, ctype = get(path, {"Host": HOST_AUTH, "Cookie": cookie or ""})
        print(f"{name} -> HTTP {status}  {len(body)} 字节  {ctype}")
