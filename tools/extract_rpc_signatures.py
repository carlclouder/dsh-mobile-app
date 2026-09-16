"""提取新版 dsh 关键 RPC 的参数签名（从 dsh-client-connection 的 typert 描述符片段）。"""
import os
import re

ROOT = r"C:\Users\Carl\AppData\Roaming\npm\node_modules\@deepseek-ai\dsh\node_modules\@deepseek-ai"
TARGETS = ["session/page", "session/prompt", "session/create", "session/follow",
           "session/updateQueue", "workspace", "session/selectModel", "session/cancel"]

# 收集所有 js 内容
blobs = {}
for dirpath, dirs, files in os.walk(ROOT):
    for fn in files:
        if not fn.endswith(".js"):
            continue
        path = os.path.join(dirpath, fn)
        try:
            blobs[path] = open(path, "rb").read().decode("utf-8", "replace")
        except OSError:
            continue

for target in TARGETS:
    print(f"\n{'=' * 20} {target} {'=' * 20}")
    found = 0
    for path, data in blobs.items():
        if "dsh-client-connection" not in path:
            continue
        for m in re.finditer(re.escape(target), data):
            start = max(0, m.start() - 400)
            end = min(len(data), m.start() + 700)
            seg = data[start:end]
            # 只打印含参数线索的片段
            if re.search(r"(fields|payload|args|sessionId|request)", seg):
                cleaned = re.sub(r"\s+", " ", seg)
                print(f"--- {os.path.basename(path)} @ {m.start()} ---")
                print(cleaned[:900])
                found += 1
                break
        if found >= 2:
            break
    if found == 0:
        print("(未找到描述符片段)")
