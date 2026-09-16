"""定位新版 dsh 前端发消息时构造的 session/prompt 请求结构。"""
import os
import re

ROOT = r"C:\Users\Carl\AppData\Roaming\npm\node_modules\@deepseek-ai\dsh\node_modules\@deepseek-ai"
PATTERNS = [
    r"prompt\s*:\s*\(",
    r"session/prompt",
    r"\bprompt\(",
]

for dirpath, dirs, files in os.walk(ROOT):
    for fn in files:
        if not fn.endswith(".js"):
            continue
        path = os.path.join(dirpath, fn)
        try:
            data = open(path, "rb").read().decode("utf-8", "replace")
        except OSError:
            continue
        for pat in PATTERNS:
            for m in list(re.finditer(pat, data))[:2]:
                seg = re.sub(r"\s+", " ", data[max(0, m.start() - 350): m.start() + 650])
                # 只打印看起来是"构造请求体"的片段
                if re.search(r"(sessionId|content|queue|clientTimeZone|mode)", seg):
                    print(f"\n=== {pat} @ {path.split('@deepseek-ai')[-1]}:{m.start()} ===")
                    print(seg[:950])
                    break
