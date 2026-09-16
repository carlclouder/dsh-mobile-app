"""更新 3 个测试用例的 payload 解包：新版信封 payload.args.request（或 args._request）。

依据：新版 RPC 契约 payload 包一层 args（curl/前端源码实测）。
"""
import re
import sys

path = r"D:\AI任务\dsh-mobile-app\app\src\test\java\dev\dshmobile\DshApiClientTest.kt"
text = open(path, encoding="utf-8").read()
original = text

# 匹配两种取体方式，后面统一接一层 args 解包
pattern = re.compile(
    r'val payload = kotlinx\.serialization\.json\.Json\.parseToJsonElement\(([^)]*\(\))\)\s*\n\s*\.jsonObject\["payload"\]!!\.jsonObject'
)

replacement = (
    'val envelopePayload = kotlinx.serialization.json.Json.parseToJsonElement(\\1)\n'
    '            .jsonObject["payload"]!!.jsonObject\n'
    '        // 新版契约：payload.args.request（少数端点为 args._request）\n'
    '        val args = envelopePayload["args"]!!.jsonObject\n'
    '        val payload = (args["request"] ?: args["_request"])!!.jsonObject'
)

text, count = pattern.subn(replacement, text)
print("replaced:", count)

if count == 0:
    sys.exit(1)

with open(path, "w", encoding="utf-8", newline="") as f:
    f.write(text)
print("patched:", text != original)
