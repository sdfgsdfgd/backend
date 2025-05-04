package data

import kotlinx.serialization.Serializable

/* ---------- DTOs exposed on the public REST surface ---------- */
@Serializable
data class AskRequestDto(val prompt: String, val model: String? = null)

@Serializable
data class AskReplyDto(val text: String)
