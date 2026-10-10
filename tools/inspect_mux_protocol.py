"""查证 /api/remote.mux 的 WebSocket 帧协议（开流/数据/结束/错误）。"""
import os
import re

ROOT = r"C:\Users\Carl\AppData\Roaming\npm\node_modules\@deepseek-ai\dsh\node_modules\@deepseek-ai"
TARGET = os.path.join(ROOT, "dsh-api-gateway")
KEYS = ["class RemoteStreamMuxServer", "handleUpgrade", "parseOpenFrame", "type: \"open\"",
        "\"open\"", "streamId", "parseRemoteStreamFrame"]

for dirpath, dirs, files in os.walk(TARGET):
    for fn in files:
        if not fn.endswith(".js"):
            continue
        path = os.path.join(dirpath, fn)
        try:
            data = open(path, "rb").read().decode("utf-8", "replace")
        except OSError:
            continue
        for key in KEYS:
            hits = list(re.finditer(re.escape(key), data))
            if not hits:
                continue
            seg = re.sub(r"\s+", " ", data[max(0, hits[0].start() - 200): hits[0].start() + 900])
            print(f"\n=== {key} @ {os.path.basename(path)} ===")
            print(seg[:1000])
