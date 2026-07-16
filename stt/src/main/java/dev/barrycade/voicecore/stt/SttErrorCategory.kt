package dev.barrycade.voicecore.stt

/**
 * High-level error categories for the STT pipeline.
 * Every [SttError] must map to exactly one category.
 */
enum class SttErrorCategory {
    CAPTURE_ERROR,
    WHISPER_ERROR,
    CONFIG_ERROR,
    UNKNOWN
}

