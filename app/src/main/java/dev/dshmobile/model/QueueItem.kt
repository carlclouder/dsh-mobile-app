package dev.dshmobile.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 排队消息项（需求：编辑/删除排队消息）。
 *
 * 新版宿主（0.1.x）不再下发 `session/queue` 帧，排队状态由 `inbox` 投影承载：
 * `{ "next-turn": [...], "next-step": [...] }`。
 * - `next-turn` 项 = 排队等本轮结束后处理 → placement 记为 queued；
 * - `next-step` 项 = 用户插话、下一步边界注入 → placement 记为 steering。
 * 项字段为 `{id, content:[{type:"text",text}], source, role}`，没有 placement 字段，
 * 位置由所在数组决定。旧版 `session/queue` 帧仍按 items[*].placement 解析。
 */
data class QueueItem(
    val id: String,
    val text: String,
    val placement: String,
) {
    companion object {
        const val PLACEMENT_QUEUED = "queued"
        const val PLACEMENT_STEERING = "steering"

        /** 解析 inbox 投影为排队列表：next-turn（排队）在前，next-step（已插话）在后。 */
        fun fromInbox(inbox: JsonObject): List<QueueItem> =
            parseBucket(inbox["next-turn"] as? JsonArray, PLACEMENT_QUEUED) +
                parseBucket(inbox["next-step"] as? JsonArray, PLACEMENT_STEERING)

        private fun parseBucket(array: JsonArray?, placement: String): List<QueueItem> {
            if (array == null) return emptyList()
            return array.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val content = obj["content"] as? JsonArray
                val text = content?.mapNotNull { block ->
                    (block as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
                }?.joinToString("") ?: ""
                QueueItem(id, text, placement)
            }
        }
    }
}
