"""提取 SessionPromptRequest 的 schema（必需字段与结构）。"""
import os
import re

ROOT = r"C:\Users\Carl\AppData\Roaming\npm\node_modules\@deepseek-ai\dsh\node_modules\@deepseek-ai"
KEY = "session_prompt_parameter_0$schema"

for dirpath, dirs, files in os.walk(ROOT):
    for fn in files:
        if not fn.endswith(".js"):
            continue
        path = os.path.join(dirpath, fn)
        try:
            data = open(path, "rb").read().decode("utf-8", "replace")
        except OSError:
            continue
        idx = data.find(KEY)
        if idx < 0:
            continue
        # schema 定义通常在 "const <KEY> = {" 处
        decl = data.rfind("const ", 0, idx)
        seg = data[decl: decl + 1800] if decl > 0 else data[idx: idx + 1800]
        print(f"=== {path.split('@deepseek-ai')[-1]} ===")
        print(re.sub(r"\s+", " ", seg)[:1700])
        print()
        break
