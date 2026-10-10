"""检查 dsh-chat-import 插件是否注册了会拦截 /ws 的路由（定位 /ws 404 的最后嫌疑）。"""
import os
import re

PLUGIN = r"C:\Users\Carl\.dsh\profiles\web\node_modules\dsh-chat-import"

print("=== 插件文件 ===")
for dirpath, _dirs, files in os.walk(PLUGIN):
    for name in files:
        path = os.path.join(dirpath, name)
        print(f"  {path.replace(PLUGIN, '')}  {os.path.getsize(path)} 字节")

print("\n=== 路由注册相关代码片段 ===")
pattern = re.compile(r"(register|prefix|webServer|\.path\b|kind:\s*['\"]|match\()", re.I)
for dirpath, _dirs, files in os.walk(PLUGIN):
    for name in files:
        if not name.endswith((".js", ".mjs", ".cjs", ".ts")):
            continue
        path = os.path.join(dirpath, name)
        try:
            text = open(path, encoding="utf-8", errors="replace").read()
        except OSError:
            continue
        lines = text.splitlines()
        hits = [(i + 1, line.strip()) for i, line in enumerate(lines) if pattern.search(line)]
        if hits:
            print(f"\n--- {path.replace(PLUGIN, '')}（{len(hits)} 处） ---")
            for lineno, line in hits[:12]:
                print(f"  L{lineno}: {line[:170]}")

print("\n=== package.json 的 dsh 段 ===")
import json

pkg = json.load(open(os.path.join(PLUGIN, "package.json"), encoding="utf-8"))
print(json.dumps(pkg.get("dsh"), ensure_ascii=False, indent=2))
print("\n=== cordis.patch.yml ===")
patch = os.path.join(PLUGIN, "cordis.patch.yml")
if os.path.exists(patch):
    print(open(patch, encoding="utf-8").read())
