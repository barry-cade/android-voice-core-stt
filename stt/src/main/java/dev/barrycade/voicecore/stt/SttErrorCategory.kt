package dev.barrycade.voicecore.stt

/**
 * High-level error categories for the STT pipeline.
 * Every [SttError] must map to exactly one category.
 */
internal enum class SttErrorCategory {
    CAPTURE_ERROR,
    WHISPER_ERROR,
    VAD_ERROR,
    TIMEOUT,
    CONFIG_ERROR,
    UNKNOWN
}
