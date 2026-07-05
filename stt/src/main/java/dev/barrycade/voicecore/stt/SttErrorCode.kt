package dev.barrycade.voicecore.stt

/**
 * Closed enumeration of all possible STT subsystem error codes.
 * Every failure in the STT pipeline must map to exactly one of these values.
 */
enum class SttErrorCode {
    MODEL_LOAD_FAILED,
    INFERENCE_FAILED,
    CAPTURE_FAILED,
    VAD_FAILED,
    PIPELINE_ILLEGAL_STATE,
    INTERNAL_EXCEPTION
}
