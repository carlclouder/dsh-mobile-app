"""查看 dsh 内部认证服务接口：authenticatedUrl / browserAuth 的签名与行为。"""
import os
import re

ROOT = r"C:\Users\Carl\AppData\Roaming\npm\node_modules\@deepseek-ai\dsh\node_modules\@deepseek-ai"
KEYS = ["authenticatedUrl", "class BrowserAuth", "browserAuth =", "authorizeIndex"]

for dirpath, dirs, files in os.walk(ROOT):
    for fn in files:
        if not fn.endswith(".js"):
            continue
        path = os.path.join(dirpath, fn)
        try:
            data = open(path, "rb").read().decode("utf-8", "replace")
        except OSError:
            continue
        for key in KEYS:
            for m in list(re.finditer(re.escape(key), data))[:2]:
                seg = re.sub(r"\s+", " ", data[max(0, m.start() - 300): m.start() + 500])
                pkg = path.split("@deepseek-ai")[-1].lstrip("\\/").split("\\")[0]
                print(f"\n=== {key} @ {pkg} ===")
                print(seg[:700])
