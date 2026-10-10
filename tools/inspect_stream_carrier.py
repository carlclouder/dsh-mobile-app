"""查证新版 dsh 的 stream carrier 端点（流式 RPC 的专用通道）与握手方式。"""
import os
import re

ROOT = r"C:\Users\Carl\AppData\Roaming\npm\node_modules\@deepseek-ai\dsh\node_modules\@deepseek-ai"
KEYS = ["stream carrier", "REMOTE_STREAM", "STREAM_PATH", "registerUpgrade", "streamCarrier"]

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
            for m in hits[:2]:
                seg = re.sub(r"\s+", " ", data[max(0, m.start() - 300): m.start() + 420])
                print(f"\n=== {key} @ {pkg} ===")
                print(seg[:700])
