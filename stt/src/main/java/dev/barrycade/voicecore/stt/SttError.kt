package dev.barrycade.voicecore.stt

/**
 * Structured error object produced by every failure in the STT subsystem.
 *
 * Category is derived from [code.category] — use that instead of storing
 * a separate category field.
 *
 * @property code  The closed [SttErrorCode] categorising this failure.
 * @property message  Human-readable description of what went wrong.
 * @property utteranceId  Optional sequential ID of the current utterance.
 * @property cause  Optional originating throwable.
 * @property details  Optional human-readable bullet points for diagnostics.
 *   (e.g. "modelPath=/data/models/ggml.bin", "pcmSamples=16000").
 *   This replaces the old freeform `context` map.
 */
data class SttError(
    val code: SttErrorCode,
    val message: String,
    val utteranceId: Int? = null,
    val cause: Throwable? = null,
    val details: List<String> = emptyList()
) {
    /**
     * The high-level error category, always derived from [code.category].
     * Convenience accessor so callers can write `error.category` instead of
     * `error.code.category`.
     */
    val category: SttErrorCategory
        get() = code.category
}
