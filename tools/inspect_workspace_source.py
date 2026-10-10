"""查证新版 dsh 前端如何获取工作区列表与归档会话（workspace/list 已不存在）。"""
import os
import re

ROOT = r"C:\Users\Carl\AppData\Roaming\npm\node_modules\@deepseek-ai\dsh\node_modules\@deepseek-ai"
KEYS = ["workspace/follow", "workspace/list", "listWorkspaces", "workspaceSnapshot",
        "archivedSessionIds", "workspace/snapshot", "workspaceChanged"]

for dirpath, dirs, files in os.walk(ROOT):
    for fn in files:
        if not fn.endswith(".js"):
            continue
        path = os.path.join(dirpath, fn)
        pkg = path.split("@deepseek-ai")[-1].lstrip("\\/").split("\\")[0]
        try:
            data = open(path, "rb").read().decode("utf-8", "replace")
        except OSError:
            continue
        for key in KEYS:
            hits = list(re.finditer(re.escape(key), data))
            if not hits:
                continue
            seg = re.sub(r"\s+", " ", data[max(0, hits[0].start() - 320): hits[0].start() + 480])
            print(f"\n=== {key} @ {pkg} ({len(hits)} hits) ===")
            print(seg[:760])
            break
