"""移除 DshApiClient 里的临时诊断日志（android.util.Log 在 JVM 单测未 mock，导致 11 例失败）。"""
import sys

path = r"D:\AI任务\dsh-mobile-app\app\src\main\java\dev\dshmobile\network\DshApiClient.kt"
text = open(path, encoding="utf-8").read()

old = '''            android.util.Log.d("DshRpc", "style=$style wireMethod=$wireMethod payload=$envelope")
'''
if old not in text:
    print("PATTERN NOT FOUND")
    sys.exit(1)

text = text.replace(old, "", 1)
with open(path, "w", encoding="utf-8", newline="") as f:
    f.write(text)
print("removed debug log")
