package dev.barrycade.voicecore.stt

/**
 * Structured error object produced by every failure in the STT subsystem.
 *
 * @property code  The closed [SttErrorCode] categorising this failure.
 * @property message  Human-readable description of what went wrong.
 * @property context  Structured diagnostic fields (never raw exception dumps).
 */
data class SttError(
    val code: SttErrorCode,
    val message: String,
    val context: Map<String, Any?> = emptyMap()
)
