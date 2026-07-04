package dev.barrycade.voicecore.stt

/**
 * Closed enumeration of all possible STT subsystem error codes.
 * Every failure in the STT pipeline must map to exactly one of these values.
 */
enum class SttErrorCode {
    AUDIO_INIT_FAILED,
    AUDIO_PERMISSION_DENIED,
    AUDIO_RECORD_FAILED,
    PCM_BUFFER_OVERFLOW,
    PCM_START_FAILED,
    PCM_STOP_FAILED,
    VAD_INIT_FAILED,
    VAD_RUNTIME_ERROR,
    WHISPER_MODEL_NOT_FOUND,
    WHISPER_MODEL_LOAD_FAILED,
    WHISPER_INFERENCE_FAILED,
    WHISPER_JNI_ERROR,
    CONFIG_INVALID,
    TIMEOUT_MAX_UTTERANCE,
    LIFECYCLE_VIOLATION,
    UNKNOWN_ERROR
}
