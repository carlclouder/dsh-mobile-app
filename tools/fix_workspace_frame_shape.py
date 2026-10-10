"""修正 App 工作区流的开流载荷与帧解析（依据 Node 直连抓到的真实帧，2026-09-16）。

实测（tools/probe_remote_mux.js）：
1. open 帧的 payload 必须是 {"args": {...}}，否则服务端回
   gateway/internal "Remote payload must contain exactly one plain-object args field"；
2. baseline 帧形状为
   {"type":"item","streamId":..,"value":{"type":"baseline","value":{"items":[...],"archivedSessionIds":[...]}}}
   —— 业务数据在**内层 value** 里（此前按外层取，取不到 → 静默丢弃 → 界面工作区空白）。
   本补丁同时做容错：内层缺失时回退外层，兼容将来的扁平形状。
"""
import sys

path = r"D:\AI任务\dsh-mobile-app\app\src\main\java\dev\dshmobile\service\EventStreamService.kt"
text = open(path, encoding="utf-8").read()

# ---------- 1) open 帧 payload 修正 ----------
old_open = """            // 开流请求：{"type":"open","streamId","endpoint","payload"}（payload 为命名参数对象，本流无参）
            val openFrame = kotlinx.serialization.json.buildJsonObject {
                put("type", "open")
                put("streamId", streamId)
                put("endpoint", "workspace/follow")
                put("payload", kotlinx.serialization.json.JsonObject(emptyMap()))
            }"""
new_open = """            // 开流请求：{"type":"open","streamId","endpoint","payload"}
            // payload 必须恰含一个 args 字段（服务端实测："Remote payload must contain exactly one plain-object args field"）；
            // workspace/follow 无参 → args 为空对象。
            val openFrame = kotlinx.serialization.json.buildJsonObject {
                put("type", "open")
                put("streamId", streamId)
                put("endpoint", "workspace/follow")
                put("payload", kotlinx.serialization.json.buildJsonObject {
                    put("args", kotlinx.serialization.json.JsonObject(emptyMap()))
                })
            }"""
if old_open not in text:
    sys.exit("openFrame 锚点未找到")
text = text.replace(old_open, new_open, 1)

# ---------- 2) 帧解析：解包内层 value（含容错） ----------
old_handler = """    private fun handleWorkspaceFrame(frame: kotlinx.serialization.json.JsonObject) {
        val type = (frame["type"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: return
        when (type) {
            "baseline" -> {
                val items = (frame["items"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { el ->"""
new_handler = """    private fun handleWorkspaceFrame(wrapper: kotlinx.serialization.json.JsonObject) {
        val type = (wrapper["type"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: return
        // 业务载荷在内层 value 里（Node 直连实测：{"type":"baseline","value":{items,archivedSessionIds}}）；
        // 容错：内层缺失时回退外层，兼容扁平形状。
        val frame = (wrapper["value"] as? kotlinx.serialization.json.JsonObject) ?: wrapper
        val payload = (frame["value"] as? kotlinx.serialization.json.JsonObject) ?: frame
        when (type) {
            "baseline" -> {
                val items = (payload["items"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { el ->"""
if old_handler not in text:
    sys.exit("handleWorkspaceFrame 锚点未找到")
text = text.replace(old_handler, new_handler, 1)

# baseline 归档字段与增量帧同样改走 payload
text = text.replace(
    '                applyArchivedSet(frame["archivedSessionIds"])\n                workspaceStreamReady = true',
    '                applyArchivedSet(payload["archivedSessionIds"])\n                workspaceStreamReady = true', 1)
text = text.replace(
    '            "upsert" -> (frame["workspace"] as? kotlinx.serialization.json.JsonObject)?.let { ws ->',
    '            "upsert" -> ((payload["workspace"] ?: frame["workspace"]) as? kotlinx.serialization.json.JsonObject)?.let { ws ->', 1)
text = text.replace(
    '                val id = (frame["workspaceId"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull',
    '                val id = (payload["workspaceId"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull', 1)
text = text.replace(
    '            "archived" -> applyArchivedSet(frame["archivedSessionIds"])',
    '            "archived" -> applyArchivedSet(payload["archivedSessionIds"])', 1)

with open(path, "w", encoding="utf-8", newline="") as handle:
    handle.write(text)

check = open(path, encoding="utf-8").read()
assert 'put("args", kotlinx.serialization.json.JsonObject(emptyMap()))' in check, "args 包装缺失"
assert 'val payload = (frame["value"] as? kotlinx.serialization.json.JsonObject) ?: frame' in check, "解包缺失"
assert 'applyArchivedSet(payload["archivedSessionIds"])' in check, "归档字段未改"
print("patch ok：open 帧 args 包装 + 内层 value 解包 均已应用")
