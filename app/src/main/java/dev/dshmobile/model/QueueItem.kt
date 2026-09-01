package dev.dshmobile.model

/**
 * 排队消息项（需求：编辑/删除排队消息）。
 * 对应 session/queue 帧的 items[*]：{id, placement:"queued"|"steering"|"context", message:{...}}。
 */
data class QueueItem(
    val id: String,
    val text: String,
    val placement: String,
)
