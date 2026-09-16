"""从本机 dsh 包中提取 typert 端点名（新版 RPC 路径），用于 App 协议适配。"""
import os
import re

ROOT = r"C:\Users\Carl\AppData\Roaming\npm\node_modules\@deepseek-ai\dsh\node_modules\@deepseek-ai"
PREFIXES = ("session/", "workspace/", "subagents/", "agentPresets/", "skills/",
            "commands/", "credentials/", "settings/", "goal/", "plan/", "feedback/")
PATTERN = re.compile(r"[\"']([a-zA-Z][a-zA-Z0-9]*/[a-zA-Z][a-zA-Z0-9/]*)[\"']")

hits = {}
for dirpath, dirs, files in os.walk(ROOT):
    for fn in files:
        if not fn.endswith(".js"):
            continue
        path = os.path.join(dirpath, fn)
        try:
            data = open(path, "rb").read().decode("utf-8", "replace")
        except OSError:
            continue
        for m in PATTERN.finditer(data):
            name = m.group(1)
            if name.startswith(PREFIXES):
                pkg = path.split("@deepseek-ai")[-1].lstrip("\\/").split("\\")[0]
                hits.setdefault(name, set()).add(pkg)

for name in sorted(hits):
    print(f"{name:<34} <- {','.join(sorted(hits[name]))[:70]}")
print(f"\ntotal endpoints: {len(hits)}")
