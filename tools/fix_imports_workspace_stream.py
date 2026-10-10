"""补 EventStreamService.kt 的 kotlinx.serialization.json 导入（put/buildJsonObject 扩展）。"""
path = r"D:\AI任务\dsh-mobile-app\app\src\main\java\dev\dshmobile\service\EventStreamService.kt"
text = open(path, encoding="utf-8").read()

if "import kotlinx.serialization.json.put" in text:
    print("import 已存在，跳过")
    raise SystemExit(0)

# 找一个已有的 import 作为锚点（按字母序插到 kotlinx 段）
anchor = "import kotlinx.coroutines.delay"
if anchor not in text:
    raise SystemExit("锚点未找到")
addition = (
    "import kotlinx.serialization.json.buildJsonObject\n"
    "import kotlinx.serialization.json.put\n"
    "import kotlinx.serialization.json.contentOrNull\n"
)
if "import kotlinx.serialization.json.contentOrNull" not in text:
    pass
else:
    addition = addition.replace("import kotlinx.serialization.json.contentOrNull\n", "")

text = text.replace(anchor, anchor + "\n" + addition.rstrip("\n"), 1)
with open(path, "w", encoding="utf-8", newline="") as handle:
    handle.write(text)

check = open(path, encoding="utf-8").read()
print("put 已导入:", "import kotlinx.serialization.json.put" in check)
print("buildJsonObject 已导入:", "import kotlinx.serialization.json.buildJsonObject" in check)
print("contentOrNull 已导入:", "import kotlinx.serialization.json.contentOrNull" in check)
