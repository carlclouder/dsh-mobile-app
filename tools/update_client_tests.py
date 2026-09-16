"""更新 DshApiClientTest 的旧协议断言为新版契约（斜杠路径 + method + args 包装）。

依据（2026-09-16 实测确凿）：
- curl 对照：`POST /api/session/list` + `payload:{"args":{"_request":{}}}` → HTTP 200；
  旧式 `POST /api/session.list` + 裸 payload → 404；
- 官方前端 client.js 分发器：`case "session/list": sessionApi.list(args._request)`、
  `session/prompt`/`updateQueue`/`cancel` 等收 `args.request`。
"""
import re
import sys

path = r"D:\AI任务\dsh-mobile-app\app\src\test\java\dev\dshmobile\DshApiClientTest.kt"
text = open(path, encoding="utf-8").read()
original = text

# 1) 路径断言：/api/<ns>.<method> → /api/<ns>/<method>
text = re.sub(r'"/api/([a-zA-Z]+)\.([a-zA-Z]+)"', r'"/api/\1/\2"', text)

# 2) method 字段断言： "ns.method" → "ns/method"
text = re.sub(r'assertEquals\("([a-zA-Z]+)\.([a-zA-Z]+)", sentBody\["method"\]',
              r'assertEquals("\1/\2", sentBody["method"]', text)

with open(path, "w", encoding="utf-8", newline="") as f:
    f.write(text)

print("changed:", text != original)
print("remaining dotted-path asserts:",
      len(re.findall(r'"/api/[a-zA-Z]+\.[a-zA-Z]+"', text)))
