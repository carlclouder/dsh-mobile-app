package dev.dshmobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 交互的真实回答地址；缺省地址继续使用旧版 client-response 协议。 */
@Serializable
sealed interface InteractionTarget {
    /** 活动事件流的回答地址；clientId 随重连而变化，不能复用旧连接的地址。 */
    @Serializable
    @SerialName("remote")
    data class Remote(val clientId: String, val eventId: String) : InteractionTarget

    /** 限时提问结束后，由宿主投影确认仍可稍后回答的工具调用。 */
    @Serializable
    @SerialName("continued")
    data class Continued(
        val agentId: String,
        val callId: String,
        val generation: Long? = null,
    ) : InteractionTarget
}
