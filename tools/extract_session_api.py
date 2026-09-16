"""提取新版 dsh 前端 sessionApi / workspaceApi 的请求体构造（字段名）。"""
import os
import re

ROOT = r"C:\Users\Carl\AppData\Roaming\npm\node_modules\@deepseek-ai\dsh\node_modules\@deepseek-ai"
KEYS = ["sessionApi = ", "workspaceApi = ", "const sessionApi", "function prompt",
        "sessionApi.prompt", "updateQueue(", "history("]

for dirpath, dirs, files in os.walk(ROOT):
    for fn in files:
        if not fn.endswith(".js"):
            continue
        path = os.path.join(dirpath, fn)
        if "dsh-client-connection" not in path and "dsh-client-runtime" not in path:
            continue
        try:
            data = open(path, "rb").read().decode("utf-8", "replace")
        except OSError:
            continue
        for key in KEYS:
            idx = data.find(key)
            if idx < 0:
                continue
            seg = re.sub(r"\s+", " ", data[max(0, idx - 200): idx + 900])
            print(f"\n===== {key} @ {os.path.basename(path)}:{idx} =====")
            print(seg[:1000])
