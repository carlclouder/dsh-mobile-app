"""读取 Gradle 编译错误全文（避免 pwsh 截断）。"""
path = r"D:\dshm\build_log.txt"
raw = open(path, "rb").read()
for enc in ("utf-8", "utf-16-le"):
    try:
        text = raw.decode(enc)
        if "e: file" in text:
            break
    except UnicodeDecodeError:
        continue
lines = [ln for ln in text.splitlines() if "e: file" in ln or "error:" in ln]
for ln in lines[:8]:
    print(ln.strip()[:400])
