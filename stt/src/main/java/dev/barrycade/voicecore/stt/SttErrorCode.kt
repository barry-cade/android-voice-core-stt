package dev.barrycade.voicecore.stt

/**
 * Closed enumeration of all possible STT subsystem error codes.
 * Every failure in the STT pipeline must map to exactly one of these values.
 *
 * @property category The high-level [SttErrorCategory] that this code belongs to.
 *        This is the single source of truth for error categorization.
 *        Every [SttError] construction site uses `code.category` rather than
 *        hardcoding a category value.
 */
enum class SttErrorCode(val category: SttErrorCategory) {
    MODEL_LOAD_FAILED(SttErrorCategory.WHISPER_ERROR),
    INFERENCE_FAILED(SttErrorCategory.WHISPER_ERROR),
    CAPTURE_FAILED(SttErrorCategory.CAPTURE_ERROR),
    CONFIG_PARSE_FAILED(SttErrorCategory.CONFIG_ERROR),
    CONFIG_NOT_SET(SttErrorCategory.CONFIG_ERROR),
    PIPELINE_ILLEGAL_STATE(SttErrorCategory.UNKNOWN),
    INTERNAL_EXCEPTION(SttErrorCategory.UNKNOWN)
}

