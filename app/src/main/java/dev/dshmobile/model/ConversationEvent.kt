package dev.dshmobile.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 会话对话事件模型（M5 原生对话页）：把 session/event 帧解析为可渲染的对话消息。
 *
 * 结构对照 DSH session 历史事件实测（rpc session.history / mux session/event 帧的 data 形态）：
 * - user/message:       {role:"user", content:[{type:"text",text}], id}
 * - assistant/message:  {role:"assistant", content:[{type:"text"|"reasoning"|"tool-call",...}]}
 * - tool/result:        message.source.kind="tool", content:[{type:"tool-result", toolCallId, content:[...]}]
 * - turn/start|end / step/start|end: 生命周期标记
 * 解析容错：未知/畸形类型归一 null，由调用方忽略（服务端事件类型会演进）。
 */
sealed interface ConversationEvent {

    /** 消息 id（user/message 有 id；assistant/message 可能无，用 seq 兜底作稳定键）。 */
    val key: String

    /** 用户消息：文本内容。 */
    data class UserMessage(
        override val key: String,
        val text: String,
        val timeMillis: Long = 0,
    ) : ConversationEvent

    /** 助手消息：主文本 + 推理摘要 + 工具调用列表。 */
    data class AssistantMessage(
        override val key: String,
        val text: String,
        val reasoning: String?,
        val toolCalls: List<ToolCall> = emptyList(),
        val timeMillis: Long = 0,
    ) : ConversationEvent

    /** 工具调用（assistant/message 的 tool-call 块）。viewTitle=宿主 view.view.title 单行摘要（WebUI 对齐）。 */
    data class ToolCall(
        val id: String?,
        val name: String,
        val arguments: String?,
        val viewTitle: String? = null,
    )

    /** 工具结果（tool/result）。viewTitle=宿主 view.view.title 单行摘要（WebUI 对齐）。 */
    data class ToolResult(
        override val key: String,
        val toolName: String,
        val output: String,
        val isError: Boolean,
        val timeMillis: Long = 0,
        val viewTitle: String? = null,
    ) : ConversationEvent

    /** turn 开始（running=true，清除 thinking 收尾状态）。 */
    data class TurnStart(override val key: String, val turn: Int) : ConversationEvent

    /** turn 结束（running=false）。reason 仅带错误信息（正常完成无 message，返回 null）。 */
    data class TurnEnd(override val key: String, val turn: Int, val reason: String?) : ConversationEvent

    /**
     * 模型/代理调用错误（host/agent-error 帧，非会话事件）：额度用完、模型 API 失败等。
     * 宿主在 host 流单独推送（sessionId 在帧顶层），App 补充为会话内错误提示，用户不至误以为消息未发出。
     */
    data class AgentError(override val key: String, val message: String, val timeMillis: Long = 0) : ConversationEvent

    /**
     * 助手流式增量（assistant/chunk 的 text-delta / reasoning-delta）。
     * key 用 "turn-step-index"，同一块增量累加到同一个流式消息。
     */
    data class AssistantChunkDelta(
        override val key: String,
        val textDelta: String? = null,
        val thinkingDelta: String? = null,
    ) : ConversationEvent

    /** 未知类型。 */
    data class Unknown(override val key: String) : ConversationEvent

    /**
     * 上下文注入/召回（user/message 且 source.kind != "user"，对齐 WebUI messageDefinition 分类）。
     * UI 只显示一行来源摘要（role + label），正文 [text] 点开才展开——修复注入全文刷屏 bug。
     */
    data class ContextInjection(
        override val key: String,
        val role: String,      // "inject"=上下文注入 | "recall"=跨会话召回（对齐 WebUI contextProvenance）
        val label: String?,    // 来源名：文件路径/插件名/技能名/引用会话标签；null=来源不可读
        val text: String,      // 注入正文（默认折叠）
        val timeMillis: Long = 0,
    ) : ConversationEvent

    /** 上下文用量（assistant/chunk 的 usage 块）：输入/输出/缓存 token。 */
    data class ContextUsage(
        override val key: String,
        val inputTokens: Int,
        val outputTokens: Int,
        val cacheReadTokens: Int?,
    ) : ConversationEvent

    /** 会话当前任务列表（todo/write 事件：data.todos[{content,status}]）。 */
    data class TodoUpdate(
        override val key: String,
        val todos: List<TodoItem> = emptyList(),
    ) : ConversationEvent

    /** 单个任务项：content=标题；status=in_progress/pending/done 等。 */
    data class TodoItem(
        val content: String,
        val status: String,
    )

    companion object {
        /**
         * 解析一个 session/event 帧的 data 为对话事件。
         * @param seq 事件序号（无 id 时的稳定键来源）
         * @param data 帧的 event.data（JsonObject）
         * @param timeMillis 帧/事件的 time（毫秒），用于消息时间戳展示
         * @param viewTitle 宿主逐工具计算的单行摘要（view.view.title，WebUI 紧凑渲染对齐）；
         *                  仅 tool-call / tool-result 消费，其余事件忽略
         */
        fun parse(type: String, seq: Long, data: JsonObject, timeMillis: Long = 0, viewTitle: String? = null): ConversationEvent {
            val key = when {
                (data["id"] as? JsonPrimitive)?.contentOrNull != null -> data["id"]!!.jsonPrimitive.content
                else -> "seq-$seq"
            }
            return when (type) {
                // user/message 先看 source.kind（对齐 WebUI）：kind 缺失或 "user" 才是普通用户消息，
                // 其余一律归为上下文注入/召回（只显示来源一行，正文折叠）——修复注入全文刷屏 bug
                "user/message" -> {
                    // source 探测与 extractText 回退次序一致：先平铺 data.source，再兼容 data.message.source 嵌套
                    val source = (data["source"] as? JsonObject)
                        ?: ((data["message"] as? JsonObject)?.get("source") as? JsonObject)
                    val sourceKind = (source?.get("kind") as? JsonPrimitive)?.contentOrNull
                    if (source == null || sourceKind == "user") {
                        UserMessage(key, extractText(data), timeMillis)
                    } else {
                        ContextInjection(
                            key = key,
                            role = if (sourceKind == "session-reference") "recall" else "inject",
                            label = contextLabel(source, sourceKind),
                            text = extractText(data),
                            timeMillis = timeMillis,
                        )
                    }
                }
                "assistant/message" -> AssistantMessage(key, extractText(data), extractReasoning(data), extractToolCalls(data), timeMillis)
                "tool/result" -> ToolResult(key, extractToolName(data), extractToolOutput(data), extractToolError(data), timeMillis, viewTitle)
                "turn/start" -> TurnStart(key, (data["turn"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0)
                "turn/end" -> TurnEnd(key, (data["turn"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0, extractTurnReason(data))
                "assistant/chunk" -> parseAssistantChunk(seq, data)
                "todo/write" -> TodoUpdate(key, parseTodos(data))
                else -> Unknown(key)
            }
        }

        /** content[{type:"text",text}] 拼接主文本。 */
        private fun extractText(data: JsonObject): String {
            val content = (data["content"] as? JsonArray)
                ?: ((data["message"] as? JsonObject)?.get("content") as? JsonArray)
                ?: return ""
            return content.mapNotNull { block ->
                val obj = block as? JsonObject
                if (obj?.get("type")?.jsonPrimitive?.contentOrNull == "text") {
                    (obj["text"] as? JsonPrimitive)?.contentOrNull
                } else null
            }.joinToString("")
        }

        /**
         * 把注入来源投影为展示名（对齐 WebUI contextProvenance）：
         * session-reference→references[].label、agent-instructions→changes[].path（生产端
         * dsh-agent-instructions 实证每个 change 都带字符串 path）、plugin→source.plugin、
         * skill-invocation→source.name；数组缺失/全不可读时降级显示 kind 本身（评审 A P2-1：
         * WebUI 对这两类是 joined(...) ?? kind，不返回 null）；kind 也读不到→null（UI 隐藏来源段）。
         */
        private fun contextLabel(source: JsonObject, kind: String?): String? = when (kind) {
            "session-reference" -> joinedDistinctLabels(source, "references", "label") ?: kind
            "agent-instructions" -> joinedDistinctLabels(source, "changes", "path") ?: kind
            "plugin" -> (source["plugin"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() } ?: kind
            "skill-invocation" -> (source["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() } ?: kind
            else -> kind
        }

        /**
         * 收集 source[member] 数组里每个元素的 [field] 非空字符串，去重保序后以", "连接；
         * 一个都没有→null。对齐 WebUI 的 collect + joined 组合语义（first-seen 去重）。
         */
        private fun joinedDistinctLabels(source: JsonObject, member: String, field: String): String? {
            val arr = source[member] as? JsonArray ?: return null
            val seen = ArrayList<String>()
            for (el in arr) {
                val obj = el as? JsonObject ?: continue
                val value = (obj[field] as? JsonPrimitive)?.contentOrNull ?: continue
                if (value.isNotEmpty() && !seen.contains(value)) seen.add(value)
            }
            return if (seen.isEmpty()) null else seen.joinToString(", ")
        }

        /** assistant 的 reasoning 块文本（首块即可）。 */
        private fun extractReasoning(data: JsonObject): String? {
            val content = ((data["message"] as? JsonObject)?.get("content") as? JsonArray) ?: return null
            return content.firstOrNull { block ->
                (block as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull == "reasoning"
            }?.let { (it as JsonObject)["text"]?.jsonPrimitive?.contentOrNull }
        }

        /** assistant 的 tool-call 块列表。 */
        private fun extractToolCalls(data: JsonObject): List<ToolCall> {
            val content = ((data["message"] as? JsonObject)?.get("content") as? JsonArray) ?: return emptyList()
            return content.mapNotNull { block ->
                val obj = block as? JsonObject
                if (obj?.get("type")?.jsonPrimitive?.contentOrNull != "tool-call") return@mapNotNull null
                ToolCall(
                    id = (obj["id"] as? JsonPrimitive)?.contentOrNull,
                    name = (obj["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    arguments = (obj["arguments"] as? JsonPrimitive)?.contentOrNull,
                )
            }
        }

        /** tool/result 的工具名（source 无 name，取 message 里首个 tool-result 的 toolCallId 关联名——v1 从 tool/call 帧已有；这里尽力从 content 提取或空）。 */
        private fun extractToolName(data: JsonObject): String {
            // tool/result 帧本身不带工具名；v1 回退为空（UI 用"工具结果"），完整名需配对 tool/call
            return ""
        }

        /**
         * tool/result 输出提取（修复"工具结果空内容"：宿主实测 content 是类型化块数组）。
         * 真帧实证（本机 session.history 2026-08-29，45/45 全为此形状）：
         *   content:[{type:"tool-result", toolCallId, content:[{type:"text", text:"..."}]}]
         * 旧形状兼容：content 内层为字符串数组（历史版本帧）仍拼接。
         * 图片块显示占位（移动端不内嵌渲染图片结果）；未知块给类型占位，不再整块丢弃。
         */
        private fun extractToolOutput(data: JsonObject): String {
            val content = ((data["message"] as? JsonObject)?.get("content") as? JsonArray) ?: return ""
            return content.mapNotNull { block ->
                val obj = block as? JsonObject
                if (obj?.get("type")?.jsonPrimitive?.contentOrNull != "tool-result") return@mapNotNull null
                innerContentText(obj["content"])
            }.filter { it.isNotEmpty() }.joinToString("\n")
        }

        /** tool-result 内层 content 的文本化：字符串/字符串数组/类型化块数组三形状统一提取。 */
        private fun innerContentText(inner: JsonElement?): String = when (inner) {
            is JsonArray -> inner.mapNotNull { element ->
                when (element) {
                    // 旧形状：content 直接是字符串数组
                    is JsonPrimitive -> element.contentOrNull
                    // 宿主形状：类型化块（ContentBlock[]，见 dsh-llm ContentBlockMap）
                    is JsonObject -> when (element.get("type")?.jsonPrimitive?.contentOrNull) {
                        "text" -> (element["text"] as? JsonPrimitive)?.contentOrNull
                        "image" -> "[图片]"
                        "reasoning" -> null   // 结果内推理块不展示（罕见）
                        else -> "[块:${element.get("type")?.jsonPrimitive?.contentOrNull ?: "未知"}]"
                    }
                    else -> null
                }
            }.filterNotNull().filter { it.isNotEmpty() }.joinToString("\n")
            is JsonPrimitive -> inner.contentOrNull ?: ""
            else -> ""
        }

        private fun extractToolError(data: JsonObject): Boolean {
            return try {
                ((data["message"] as? JsonObject)?.get("content") as? JsonArray)
                    ?.any { (it as? JsonObject)?.get("isError")?.jsonPrimitive?.content == "true" } == true
            } catch (e: Exception) { false }
        }

        private fun extractTurnReason(data: JsonObject): String? {
            return try {
                val reason = data["reason"] as? JsonObject
                    ?: return (data["reason"] as? JsonPrimitive)?.contentOrNull
                // reason.message 或 reason.error.message（真实结构：错误时 message 嵌套在 error 内）
                (reason["message"] as? JsonPrimitive)?.contentOrNull
                    ?: ((reason["error"] as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
            } catch (e: Exception) { null }
        }

        /** todo/write 的 data.todos 列表。 */
        private fun parseTodos(data: JsonObject): List<TodoItem> {
            val arr = data["todos"] as? JsonArray ?: return emptyList()
            return arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val content = (o["content"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val status = (o["status"] as? JsonPrimitive)?.contentOrNull ?: "pending"
                TodoItem(content, status)
            }
        }

        /** assistant/chunk 增量：仅取 text-delta / reasoning-delta；其余 chunk 类型归一 Unknown。 */
        private fun parseAssistantChunk(seq: Long, data: JsonObject): ConversationEvent {
            val chunk = data["chunk"] as? JsonObject ?: return Unknown("seq-$seq")
            val ctype = (chunk["type"] as? JsonPrimitive)?.contentOrNull ?: return Unknown("seq-$seq")
            val turn = (data["turn"] as? JsonPrimitive)?.contentOrNull ?: "0"
            val step = (data["step"] as? JsonPrimitive)?.contentOrNull ?: "0"
            val index = (chunk["index"] as? JsonPrimitive)?.contentOrNull ?: "0"
            val key = "$turn-$step-$index"
            return when (ctype) {
                "text-delta" -> AssistantChunkDelta(
                    key, textDelta = (chunk["text"] as? JsonPrimitive)?.contentOrNull)
                "reasoning-delta" -> AssistantChunkDelta(
                    key, thinkingDelta = (chunk["text"] as? JsonPrimitive)?.contentOrNull)
                "usage" -> {
                    val usage = chunk["usage"] as? JsonObject
                    ContextUsage(
                        key = key,
                        inputTokens = (usage?.get("inputTokens") as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
                        outputTokens = (usage?.get("outputTokens") as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
                        cacheReadTokens = (usage?.get("cacheReadTokens") as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
                    )
                }
                else -> Unknown(key)
            }
        }
    }
}
