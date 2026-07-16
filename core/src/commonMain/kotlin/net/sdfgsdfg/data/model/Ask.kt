package net.sdfgsdfg.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AskRequestDto(
    val prompt: String,
    val model: String? = null,
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("new_chat") val newChat: Boolean = false,
    @SerialName("want_tts") val wantTts: Boolean = false,
    val deepseek: Boolean = false,
    @SerialName("deepseek_search") val deepseekSearch: Boolean = false,
    @SerialName("one_time") val oneTime: Boolean = false,
    @SerialName("no_pace") val noPace: Boolean = false,
    @SerialName("pace_min_seconds") val paceMinSeconds: Double? = null,
    @SerialName("pace_max_seconds") val paceMaxSeconds: Double? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("new_tab") val newTab: Boolean = false,
    @SerialName("end_session") val endSession: Boolean = false,
)

@Serializable
data class AskReplyDto(
    val text: String,
    val ttsMp3: String? = null,
)
