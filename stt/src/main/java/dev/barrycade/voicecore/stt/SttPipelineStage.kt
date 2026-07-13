package dev.barrycade.voicecore.stt

/**
 * Deterministic runtime pipeline stages for a session lifecycle.
 */
internal enum class SttPipelineStage {
    IDLE,
    CAPTURING,
    FINALISING,
    INFERENCING,
    DISPATCHING
}
