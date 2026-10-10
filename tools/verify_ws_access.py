"""验证 /ws 路由对证据文件的可访问性（python urllib 正确处理 UTF-8 路径编码）。"""
import urllib.parse
import urllib.request

FILES = ["before_workspace_empty.png", "after_workspace_restored.png", "workspace_switch.gif"]
BASE = "D:/AI任务/dsh-mobile-app/test-evidence/workspace-stream-20260916"

for name in FILES:
    raw = f"http://127.0.0.1:3080/ws/{BASE}/{name}"
    encoded = urllib.parse.quote(raw, safe=":/?=&")
    try:
        with urllib.request.urlopen(encoded, timeout=10) as resp:
            data = resp.read()
            print(f"{name} -> HTTP {resp.status}  {len(data)} 字节  {resp.headers.get('content-type')}")
    except Exception as error:
        print(f"{name} -> 失败: {error}")
    print("  URL:", encoded)
