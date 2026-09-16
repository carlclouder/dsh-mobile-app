"""查找 WebUI 前端发送消息时 session/prompt 的 mode 取值与调用点。"""
import os
import re

ROOT = r"C:\Users\Carl\AppData\Roaming\npm\node_modules\@deepseek-ai\dsh\node_modules\@deepseek-ai"
PATTERN = re.compile(r'mode:\s*"?(queue|steer|immediate|send)"?')

for dirpath, dirs, files in os.walk(ROOT):
    for fn in files:
        if not fn.endswith(".js"):
            continue
        path = os.path.join(dirpath, fn)
        try:
            data = open(path, "rb").read().decode("utf-8", "replace")
        except OSError:
            continue
        hits = list(PATTERN.finditer(data))
        if not hits:
            continue
        pkg = path.split("@deepseek-ai")[-1].lstrip("\\/")
        print(f"\n=== {pkg} ({len(hits)} hits) ===")
        shown = 0
        for m in hits:
            seg = re.sub(r"\s+", " ", data[max(0, m.start() - 260): m.start() + 200])
            # 只展示与 prompt/发送相关的
            if re.search(r"(prompt|send|submit|composer)", seg, re.I):
                print(f"  @{m.start()}: {seg[:420]}")
                shown += 1
                if shown >= 3:
                    break
