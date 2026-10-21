package dev.dshmobile.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.TreeMap

/** 当前生成尾部的思考内容；键变化时页面重置折叠行的展示状态。 */
data class ThinkingPreview(val key: String = "", val content: String = "")

/** 只归约思考预览，不向消息历史插入临时事件。false 表示必须重新获取完整快照。 */
class ThinkingPreviewState {
    private data class Block(val kind: String, val text: StringBuilder = StringBuilder())

    private val blocks = TreeMap<Long, Block>()
    private var opened = false
    private var revision = 0L
    private var attemptId: String? = null
    private var nextIndex = 0L

    /** 先检查是否有内容，避免每个增量都复制不断增长的字符串。 */
    val hasPreview: Boolean
        get() = attemptId != null && blocks.lastEntry()?.value?.let {
            it.kind == "reasoning" && it.text.isNotEmpty()
        } == true

    val preview: ThinkingPreview
        get() {
            if (!hasPreview) return ThinkingPreview()
            val tail = blocks.lastEntry() ?: return ThinkingPreview()
            return ThinkingPreview("$attemptId:${tail.key}", tail.value.text.toString())
        }

    fun reset() {
        opened = false
        revision = 0L
        clearAttempt()
    }

    fun accept(value: JsonObject): Boolean {
        val accepted = when (value.string("type")) {
            "snapshot" -> replace(value)
            "assistant-stream" -> opened && (value["frame"] as? JsonObject)?.let(::acceptFrame) == true
            "event" -> opened
            else -> false
        }
        if (!accepted) reset()
        return accepted
    }

    private fun replace(value: JsonObject): Boolean {
        reset()
        val baseline = value["assistantStream"] as? JsonObject ?: return false
        revision = baseline.number("revision") ?: return false
        val opening = baseline["activeAttempt"]
        if (opening != null) {
            val attempt = opening as? JsonObject ?: return false
            attemptId = attempt.string("attemptId")?.takeIf { it.isNotBlank() } ?: return false
            if (!validAttempt(attempt)) return false
            val expectedIndex = attempt.number("nextIndex") ?: return false
            val records = attempt["stream"] as? JsonArray ?: return false
            var expandedCount = 0L
            for (element in records) {
                val record = element as? JsonObject ?: return false
                val count = acceptRecord(record) ?: return false
                if (count > expectedIndex - expandedCount) return false
                expandedCount += count
            }
            // nextIndex 计算原始 chunk 数，不能把压缩后的记录条数当成接续游标。
            if (expandedCount != expectedIndex) return false
            nextIndex = expectedIndex
        }
        opened = true
        return true
    }

    private fun acceptFrame(frame: JsonObject): Boolean {
        val incomingRevision = frame.number("revision") ?: return false
        // 重复、缺口和宿主归零都重新取基线，不能把归零后的新生成当成旧帧丢弃。
        if (incomingRevision != revision + 1) return false
        revision = incomingRevision
        val incomingAttempt = frame.string("attemptId")?.takeIf { it.isNotBlank() } ?: return false
        when (frame.string("type")) {
            "start" -> {
                if (!validAttempt(frame)) return false
                clearAttempt()
                attemptId = incomingAttempt
            }
            "chunk" -> {
                if (attemptId != incomingAttempt || frame.number("index") != nextIndex) return false
                val chunk = frame["chunk"] as? JsonObject ?: return false
                if (!acceptChunk(chunk)) return false
                nextIndex += 1
            }
            "end" -> {
                if (attemptId != incomingAttempt || frame.number("index") != nextIndex) return false
                val outcome = frame["outcome"] as? JsonObject ?: return false
                when (outcome.string("kind")) {
                    "committed" -> if (outcome.string("eventType") !in setOf("assistant/message", "assistant/attempt") ||
                        outcome.number("seq") == null) return false
                    "abandoned" -> Unit
                    else -> return false
                }
                clearAttempt()
            }
            else -> return false
        }
        return true
    }

    private fun validAttempt(attempt: JsonObject): Boolean =
        attempt.number("turn") != null && attempt.number("step") != null &&
            attempt.number("startedAfterSeq", minimum = -1L) != null

    private fun acceptRecord(record: JsonObject): Long? {
        val type = record.string("type") ?: return null
        if (type == "chunk") {
            if (record.number("time") == null) return null
            val chunk = record["chunk"] as? JsonObject ?: return null
            return if (acceptChunk(chunk)) 1L else null
        }
        val index = record.number("index") ?: return null
        val members = record[if (type == "tool-call-chunks") "args" else "texts"] as? JsonArray ?: return null
        val gaps = record["dt"] as? JsonArray ?: return null
        if (members.isEmpty() || gaps.size != members.size - 1 || record.number("time0") == null ||
            gaps.any { it.integer(minimum = -MAX_SAFE_INTEGER) == null }) return null
        val texts = members.map { (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.content ?: return null }
        when (type) {
            "reasoning-chunks" -> appendReasoning(index, texts.joinToString(""))
            "text-chunks" -> blocks[index] = Block("text")
            "tool-call-chunks" -> {
                if (record.string("id").isNullOrEmpty() ||
                    (record.containsKey("name") && record.string("name").isNullOrEmpty())) return null
                blocks[index] = Block("tool-call")
            }
            else -> return null
        }
        return members.size.toLong()
    }

    private fun acceptChunk(chunk: JsonObject): Boolean {
        when (chunk.string("type")) {
            "block-start" -> {
                val index = chunk.number("index") ?: return false
                blocks[index] = Block(chunk.string("blockType") ?: return false)
            }
            "reasoning-delta" -> {
                val index = chunk.number("index") ?: return false
                appendReasoning(index, chunk.string("text") ?: return false)
            }
            "text-delta", "tool-call-delta" -> {
                val index = chunk.number("index") ?: return false
                val kind = if (chunk.string("type") == "text-delta") "text" else "tool-call"
                if (chunk.string(if (kind == "text") "text" else "argumentsDelta") == null) return false
                blocks[index] = Block(kind)
            }
            "block-end" -> {
                val index = chunk.number("index") ?: return false
                val block = chunk["block"] as? JsonObject ?: return false
                val kind = block.string("type") ?: return false
                val text = if (kind == "reasoning") block.string("text") ?: return false else ""
                // 尾块由位置决定；迟到的旧块结尾只能替换旧块，不能抢回当前正文的位置。
                blocks[index] = Block(kind, StringBuilder(text))
            }
            "usage", "finish" -> Unit
            else -> return false
        }
        return true
    }

    private fun appendReasoning(index: Long, text: String) {
        val previous = blocks[index]
        val block = if (previous?.kind == "reasoning") previous else Block("reasoning").also { blocks[index] = it }
        block.text.append(text)
    }

    private fun clearAttempt() {
        attemptId = null
        nextIndex = 0L
        blocks.clear()
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.number(key: String, minimum: Long = 0L): Long? = this[key]?.integer(minimum)

    private fun JsonElement.integer(minimum: Long): Long? =
        (this as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in minimum..MAX_SAFE_INTEGER }

    companion object {
        private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    }
}
