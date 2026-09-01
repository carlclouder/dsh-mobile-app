package dev.dshmobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 协议数据模型（M2）。
 *
 * 全部字段名与可空性与 DSH 服务端 zod schema 逐一对照钉死：
 * - 信封/回执：dsh-host-apiproxy/lib/types/api/rpc.schema.js
 * - 会话摘要：sessions.schema.js L31-49（updatedAt 为数值时间戳）
 * - 工作区视图：workspace.schema.js L10-24（createdAt/updatedAt 为 ISO 字符串）
 * - 帧联合：events.schema.js（MuxFrame L34-58 / HostFrame L60-83）
 *
 * 解析姿态（对齐浏览器客户端 client.js L10144-10154 的两级解析）：
 * 先 JSON.parse 信封，再按 payload.type 分发；未知 type 或字段缺失不抛异常，
 * 归一为 Unknown*Frame / null，由调用方记日志忽略——服务端升级新增帧类型时 APP 不崩。
 */

/** 全局 JSON 实例：容忍未知字段（服务端帧带可选扩展字段），不强制显式 null。 */
val DshJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}

// ---------------------------------------------------------------------------
// RPC 信封（四种线上形态中 APP 用到的三种；client-request 由客户端构造无需反序列化）
// ---------------------------------------------------------------------------

/** 服务端请求信封：两条事件 WebSocket 的每条文本消息。rpcId 在信封层（非帧内）。 */
@Serializable
data class ServerRequestEnvelope(
    val type: String,
    val rpcId: String,
    val method: String,
    val payload: JsonObject,
)

/** 服务端响应信封：POST /api/<method> 的响应体。 */
@Serializable
data class ServerResponseEnvelope(
    val type: String,
    val rpcId: String,
    val result: RpcResult,
)

/** 业务结果分支：ok=true 带 value（void 调用可能省略）；ok=false 带 error。 */
@Serializable
data class RpcResult(
    val ok: Boolean,
    val value: JsonElement? = null,
    val error: RpcError? = null,
)

/** 错误体：code 为判别字段（bad-request/session-not-found/internal 等），details 结构随 code 变化故保持宽 JSON。 */
@Serializable
data class RpcError(
    val code: String,
    val message: String,
    val details: JsonObject = JsonObject(emptyMap()),
)

// ---------------------------------------------------------------------------
// /api/respond 回执（rpc.schema.js L110-113：三态，not-pending 单列供撤通知语义）
// ---------------------------------------------------------------------------

/** 回执原始形态：accepted=true 无 reason；accepted=false 必带 reason。 */
@Serializable
private data class RespondReceiptWire(
    val accepted: Boolean,
    val reason: String? = null,
)

/** 回执三态密封层级：调用方按类型分支处理（NotPending = 已在别处处理，撤通知不重试）。 */
sealed interface RespondReceipt {
    data object Accepted : RespondReceipt
    data class NotPending(val rawReason: String?) : RespondReceipt
    data class BadResponse(val rawReason: String?) : RespondReceipt

    /** 传输层故障（DNS/TLS/超时/非 2xx）：与回执体畸形分离，上层可区分"可重试"与"别重试"（评审 B P1-3）。 */
    data class Transport(val description: String) : RespondReceipt

    /** 2xx 但回执体不可解析（非 JSON / 缺 reason 等）。 */
    data class Malformed(val rawBody: String?) : RespondReceipt

    companion object {
        fun parse(body: String): RespondReceipt {
            // 容错分支：非 JSON 响应（网关错误页等）归一为 Malformed，不抛异常
            return try {
                val wire = DshJson.decodeFromString(RespondReceiptWire.serializer(), body)
                when {
                    wire.accepted -> Accepted
                    wire.reason == "not-pending" -> NotPending(wire.reason)
                    wire.reason == "bad-response" -> BadResponse(wire.reason)
                    else -> Malformed(wire.reason)
                }
            } catch (e: Throwable) {
                Malformed(body.take(200))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// session.list / workspace.list 响应值
// ---------------------------------------------------------------------------

/**
 * 会话摘要行（sessions.schema.js sessionSummarySchema）。
 * updatedAt 语义 = 创建时间与最近人类提交 prompt 时间的较晚者（非最近活动时间）。
 */
@Serializable
data class SessionSummary(
    val sessionId: String,
    val updatedAt: Double,
    val running: Boolean,
    val blank: Boolean,
    val parentSessionId: String? = null,
    /** 目前线上仅 "subagent"；null = 主会话。 */
    val origin: String? = null,
    val cwd: String? = null,
    val agentPreset: String? = null,
    val projections: ProjectionsBlock? = null,
) {
    /** 列表标题：projections.values.title（以实测定型的常量路径，单测锁死）。非原始类型/异常 → null（评审 B P2-8）。 */
    fun titleOrNull(): String? = try {
        projections?.values?.get(TITLE_PROJECTION_KEY)
            ?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    companion object {
        const val TITLE_PROJECTION_KEY = "title"
    }
}

/** 投影基线块：values 保持宽 JSON（各领域 schema 归属宿主侧）。asOfSeq=-1 表示空日志。 */
@Serializable
data class ProjectionsBlock(
    val asOfSeq: Long,
    val values: JsonObject,
)

/** session.list 响应值。 */
@Serializable
data class SessionListValue(
    val items: List<SessionSummary> = emptyList(),
)

/** 工作区视图（workspace.schema.js workspaceViewSchema）。注意 createdAt/updatedAt 是字符串（schema 必填，无默认值：畸形帧走丢弃路径）。 */
@Serializable
data class WorkspaceView(
    val workspaceId: String,
    val path: String,
    val title: String,
    val sessionIds: List<String>,
    val createdAt: String,
    val updatedAt: String,
)

/** workspace.list 响应值。 */
@Serializable
data class WorkspaceListValue(
    val items: List<WorkspaceView> = emptyList(),
    val archivedSessionIds: List<String> = emptyList(),
)

// ---------------------------------------------------------------------------
// question/requested 载荷（events.schema.js askUserQuestionItemSchema）
// ---------------------------------------------------------------------------

/** 单个问题的选项。 */
@Serializable
data class QuestionOption(
    val label: String,
    val description: String? = null,
)

/** Agent 提问条目：intent 为线上标记联合，保持宽 JSON 原样透传。 */
@Serializable
data class QuestionItem(
    val id: String,
    val question: String,
    val header: String? = null,
    val detail: String? = null,
    val options: List<QuestionOption> = emptyList(),
    val multiSelect: Boolean? = null,
    val intent: JsonObject? = null,
)

/** 提问应答条目（questions.schema.js askUserQuestionAnswerSchema 内层）。 */
@Serializable
data class QuestionAnswerItem(
    val id: String,
    val selected: List<String> = emptyList(),
    val custom: String? = null,
)

// ---------------------------------------------------------------------------
// MuxFrame：/api/events.mux 下行帧（events.schema.js muxFrameSchema）
// ---------------------------------------------------------------------------

sealed interface MuxFrame {
    val sessionIdOrNull: String?

    /** 会话事件：event 保持宽 JSON（严格信封 type/seq/time + 宽 data 的透传设计）；view=宿主逐工具单行摘要（可缺省）。 */
    @Serializable
    data class SessionEvent(
        val sessionId: String,
        val event: JsonObject,
        val view: JsonObject? = null,
    ) : MuxFrame {
        override val sessionIdOrNull: String get() = sessionId
    }

    @Serializable
    data class SessionSubscribed(
        val sessionId: String,
        val lastSeq: Long,
    ) : MuxFrame {
        override val sessionIdOrNull: String get() = sessionId
    }

    /** 审批请求：通知的核心信号之一，approvalId 为去重键。 */
    @Serializable
    data class ApprovalRequested(
        val sessionId: String,
        val approvalId: String,
        val toolName: String,
        val callId: String? = null,
        val reason: String? = null,
    ) : MuxFrame {
        override val sessionIdOrNull: String get() = sessionId
    }

    /** 审批已被处理（含 cancelled/unavailable）：撤对应通知。 */
    @Serializable
    data class ApprovalResolved(
        val sessionId: String,
        val approvalId: String,
        val outcome: String,
    ) : MuxFrame {
        override val sessionIdOrNull: String get() = sessionId
    }

    /** Agent 提问：通知的核心信号之一，信封层 rpcId 为去重键。 */
    @Serializable
    data class QuestionRequested(
        val sessionId: String,
        val questions: List<QuestionItem>,
    ) : MuxFrame {
        override val sessionIdOrNull: String get() = sessionId
    }

    @Serializable
    data class QuestionResolved(
        val sessionId: String,
        val questionRpcId: String,
        val outcome: String,
    ) : MuxFrame {
        override val sessionIdOrNull: String get() = sessionId
    }

    /** 队列快照：items 保持宽 JSON（本 APP 不消费对话细节）。 */
    @Serializable
    data class SessionQueue(
        val sessionId: String,
        val items: JsonArray,
    ) : MuxFrame {
        override val sessionIdOrNull: String get() = sessionId
    }

    /** 任务视图快照：jobs 保持宽 JSON。 */
    @Serializable
    data class SessionJobs(
        val sessionId: String,
        val jobs: JsonArray,
    ) : MuxFrame {
        override val sessionIdOrNull: String get() = sessionId
    }

    /** 投影更新：title 等列表显示名的增量来源。 */
    @Serializable
    data class SessionProjection(
        val sessionId: String,
        val key: String,
        val value: JsonElement,
        val seq: Long,
    ) : MuxFrame {
        override val sessionIdOrNull: String get() = sessionId
    }

    /** 流错误：记日志并触发该流重连（设计 §3.2）。 */
    @Serializable
    data class StreamError(
        val error: RpcError,
    ) : MuxFrame {
        override val sessionIdOrNull: String? get() = null
    }

    /** 未知帧类型：服务端未来扩展，记日志忽略（不崩）。 */
    data class Unknown(val type: String) : MuxFrame {
        override val sessionIdOrNull: String? get() = null
    }
}

// ---------------------------------------------------------------------------
// HostFrame：/api/events.host 下行帧（events.schema.js hostFrameSchema）
// ---------------------------------------------------------------------------

sealed interface HostFrame {
    val sessionIdOrNull: String?

    /** 会话新增：origin=subagent 的会话不通知、列表默认隐藏（设计 §3.2 P1 策略）。 */
    @Serializable
    data class SessionAdded(
        val sessionId: String,
        val blank: Boolean,
        val parentSessionId: String? = null,
        val origin: String? = null,
        val cwd: String? = null,
        val agentPreset: String? = null,
    ) : HostFrame {
        override val sessionIdOrNull: String get() = sessionId
    }

    @Serializable
    data class SessionRemoved(val sessionId: String) : HostFrame {
        override val sessionIdOrNull: String get() = sessionId
    }

    /** running 翻转：回合完成通知的核心信号。 */
    @Serializable
    data class SessionStatus(val sessionId: String, val running: Boolean) : HostFrame {
        override val sessionIdOrNull: String get() = sessionId
    }

    /** agent 错误：记日志 + 列表徽标，不弹通知（避免与完成通知叠加）。 */
    @Serializable
    data class AgentError(val sessionId: String, val message: String) : HostFrame {
        override val sessionIdOrNull: String get() = sessionId
    }

    @Serializable
    data class WorkspaceChanged(val workspace: WorkspaceView) : HostFrame {
        override val sessionIdOrNull: String? get() = null
    }

    @Serializable
    data class WorkspaceRemoved(val workspaceId: String) : HostFrame {
        override val sessionIdOrNull: String? get() = null
    }

    @Serializable
    data class WorkspaceOrderChanged(val workspaceIds: List<String>) : HostFrame {
        override val sessionIdOrNull: String? get() = null
    }

    @Serializable
    data class ArchivedSessionsChanged(val archivedSessionIds: List<String>) : HostFrame {
        override val sessionIdOrNull: String? get() = null
    }

    /** 远端事件：args 保持宽 JSON。 */
    @Serializable
    data class RemoteEvent(val event: String, val args: JsonArray) : HostFrame {
        override val sessionIdOrNull: String? get() = null
    }

    @Serializable
    data class StreamError(val error: RpcError) : HostFrame {
        override val sessionIdOrNull: String? get() = null
    }

    /** 未知帧类型：记日志忽略。 */
    data class Unknown(val type: String) : HostFrame {
        override val sessionIdOrNull: String? get() = null
    }
}

// ---------------------------------------------------------------------------
// 帧解析器：两级解析的姿态与浏览器客户端一致（先信封后按 type 分发），
// 与 kotlinx 多态注解相比，显式 when 映射能干净地容纳 Unknown 分支。
// ---------------------------------------------------------------------------

object FrameParser {

    /** 解析 WS 文本消息为信封；非 JSON / 缺字段 / payload 非对象 → null（调用方丢弃并记日志）。 */
    fun parseEnvelope(text: String): ServerRequestEnvelope? {
        return try {
            DshJson.decodeFromString(ServerRequestEnvelope.serializer(), text)
        } catch (e: Throwable) {
            // Throwable 而非 Exception：深嵌套 JSON 的 StackOverflowError 也归一为丢弃/null（评审 B P2-8）
            null
        }
    }

    /** 解析 POST 响应体为服务端响应信封；畸形 → null。 */
    fun parseServerResponse(text: String): ServerResponseEnvelope? {
        return try {
            DshJson.decodeFromString(ServerResponseEnvelope.serializer(), text)
        } catch (e: Throwable) {
            // Throwable 而非 Exception：深嵌套 JSON 的 StackOverflowError 也归一为丢弃/null（评审 B P2-8）
            null
        }
    }

    /** mux 帧分发：未知 type → Unknown；已知 type 字段畸形 → null（丢弃，对齐浏览器 drop 策略）。 */
    fun parseMuxFrame(payload: JsonObject): MuxFrame? {
        val type = (payload["type"] as? JsonPrimitive)?.contentOrNull ?: return null
        return try {
            when (type) {
                "session/event" -> DshJson.decodeFromJsonElement(MuxFrame.SessionEvent.serializer(), payload)
                "session/subscribed" -> DshJson.decodeFromJsonElement(MuxFrame.SessionSubscribed.serializer(), payload)
                "approval/requested" -> DshJson.decodeFromJsonElement(MuxFrame.ApprovalRequested.serializer(), payload)
                "approval/resolved" -> DshJson.decodeFromJsonElement(MuxFrame.ApprovalResolved.serializer(), payload)
                "question/requested" -> DshJson.decodeFromJsonElement(MuxFrame.QuestionRequested.serializer(), payload)
                "question/resolved" -> DshJson.decodeFromJsonElement(MuxFrame.QuestionResolved.serializer(), payload)
                "session/queue" -> DshJson.decodeFromJsonElement(MuxFrame.SessionQueue.serializer(), payload)
                "session/jobs" -> DshJson.decodeFromJsonElement(MuxFrame.SessionJobs.serializer(), payload)
                "session/projection" -> DshJson.decodeFromJsonElement(MuxFrame.SessionProjection.serializer(), payload)
                "stream/error" -> DshJson.decodeFromJsonElement(MuxFrame.StreamError.serializer(), payload)
                else -> MuxFrame.Unknown(type)
            }
        } catch (e: Throwable) {
            // Throwable 而非 Exception：深嵌套 JSON 的 StackOverflowError 也归一为丢弃/null（评审 B P2-8）
            null
        }
    }

    /** host 帧分发：语义同 [parseMuxFrame]。 */
    fun parseHostFrame(payload: JsonObject): HostFrame? {
        val type = (payload["type"] as? JsonPrimitive)?.contentOrNull ?: return null
        return try {
            when (type) {
                "host/session-added" -> DshJson.decodeFromJsonElement(HostFrame.SessionAdded.serializer(), payload)
                "host/session-removed" -> DshJson.decodeFromJsonElement(HostFrame.SessionRemoved.serializer(), payload)
                "host/session-status" -> DshJson.decodeFromJsonElement(HostFrame.SessionStatus.serializer(), payload)
                "host/agent-error" -> DshJson.decodeFromJsonElement(HostFrame.AgentError.serializer(), payload)
                "host/workspace-changed" -> DshJson.decodeFromJsonElement(HostFrame.WorkspaceChanged.serializer(), payload)
                "host/workspace-removed" -> DshJson.decodeFromJsonElement(HostFrame.WorkspaceRemoved.serializer(), payload)
                "host/workspace-order-changed" -> DshJson.decodeFromJsonElement(HostFrame.WorkspaceOrderChanged.serializer(), payload)
                "host/archived-sessions-changed" -> DshJson.decodeFromJsonElement(HostFrame.ArchivedSessionsChanged.serializer(), payload)
                "host/remote-event" -> DshJson.decodeFromJsonElement(HostFrame.RemoteEvent.serializer(), payload)
                "stream/error" -> DshJson.decodeFromJsonElement(HostFrame.StreamError.serializer(), payload)
                else -> HostFrame.Unknown(type)
            }
        } catch (e: Throwable) {
            // Throwable 而非 Exception：深嵌套 JSON 的 StackOverflowError 也归一为丢弃/null（评审 B P2-8）
            null
        }
    }
}

// ---------------------------------------------------------------------------
// ApiClient 结果类型（设计 §5.1：Ok / BizError / NetError 三分支）
// ---------------------------------------------------------------------------

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class BizError(val code: String, val message: String) : ApiResult<Nothing>
    data class NetError(val throwable: Throwable) : ApiResult<Nothing>
}
