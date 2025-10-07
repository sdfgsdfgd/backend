package net.sdfgsdfg.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/* ---------- DTOs exposed on the public REST surface ---------- */
@Serializable
data class AskRequestDto(
    val prompt: String,
    val model: String? = null,
    @SerialName("new_chat") val newChat: Boolean = false,
    @SerialName("want_tts") val wantTts: Boolean = false,
)

@Serializable
data class AskReplyDto(
    val text: String,
    val ttsMp3: String? = null,
)
