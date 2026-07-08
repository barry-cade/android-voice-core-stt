package dev.barrycade.voicecore.stt

/**
 * Result of a completed STT session.
 *
 * @property code The [SttReturnCode] categorising the outcome.
 * @property transcript The transcribed text, or null when no speech was produced.
 */
internal data class SessionResult(
    val code: SttReturnCode,
    val transcript: String? = null
)
