package dev.barrycade.voicecore.stt

/**
 * Result of a completed STT session.
 *
 * Contains only a return code and an optional transcript.
 * No human-readable messages — the caller interprets the [code].
 *
 * @property code The [SttReturnCode] categorising the outcome.
 * @property transcript The transcribed text, or null when no speech was produced
 *                      or when the session terminated without transcription.
 */
internal data class SessionResult(
    val code: SttReturnCode,
    val transcript: String?
)
