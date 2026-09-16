"""给 DshApiClient.sessionPrompt 补 requestId 字段（新版 schema 必需）。"""
import sys

path = r"D:\AI任务\dsh-mobile-app\app\src\main\java\dev\dshmobile\network\DshApiClient.kt"
text = open(path, encoding="utf-8").read()

old = '''    suspend fun sessionPrompt(sessionId: String, text: String): ApiResult<Unit> {
        val payload = buildJsonObject {
            put("sessionId", sessionId)'''

new = '''    suspend fun sessionPrompt(sessionId: String, text: String): ApiResult<Unit> {
        val payload = buildJsonObject {
            // 新版 SessionPromptRequest schema 要求 requestId（幂等标识；缺失即 boundary validation 失败）
            put("requestId", UUID.randomUUID().toString())
            put("sessionId", sessionId)'''

if old not in text:
    print("PATTERN NOT FOUND")
    sys.exit(1)

text = text.replace(old, new, 1)
with open(path, "w", encoding="utf-8", newline="") as f:
    f.write(text)
print("patched ok")
