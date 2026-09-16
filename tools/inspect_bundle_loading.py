"""查证 dsh 的 bundle 加载方式：patch 的 name 是否支持本地路径（决定插件能否免安装伴随 dsh）。"""
import os
import re

ROOT = r"C:\Users\Carl\AppData\Roaming\npm\node_modules\@deepseek-ai\dsh\node_modules\@deepseek-ai"
KEYS = ["resolveBundle", "loadBundle", "bundleEntry", "import(", "createRequire"]

hits = 0
for dirpath, dirs, files in os.walk(ROOT):
    for fn in files:
        if not fn.endswith(".js"):
            continue
        path = os.path.join(dirpath, fn)
        pkg = path.split("@deepseek-ai")[-1].lstrip("\\/").split("\\")[0]
        if pkg not in ("dsh-app-boot", "dsh-boot", "dsh-base"):
            continue
        try:
            data = open(path, "rb").read().decode("utf-8", "replace")
        except OSError:
            continue
        for key in KEYS:
            for m in list(re.finditer(re.escape(key), data))[:1]:
                seg = re.sub(r"\s+", " ", data[max(0, m.start() - 260): m.start() + 420])
                print(f"\n=== {key} @ {pkg} ===")
                print(seg[:640])
                hits += 1
                break
print("\nhits:", hits)
