package dev.barrycade.voicecore.stt

/**
 * Closed enumeration of all possible STT session return codes.
 *
 * Every completed session produces exactly one [SttReturnCode] that
 * categorises the outcome. The caller is responsible for interpreting
 * the code and presenting any user-facing messages.
 *
 * ## New API codes (Phase 1+)
 * These codes are used by [SessionResult] returned from [SpeechToText.startSession]:
 *
 * - [SUCCESS] — utterance was transcribed successfully.
 * - [CONFIG_NOT_SET] — [SpeechToText.setConfig] was not called before [startSession].
 * - [INVALID_CONFIG] — config validation failed (see [SttRunConfigValidator]).
 * - [MAX_DURATION_REACHED] — max utterance duration exceeded.
 * - [AUTO_SILENCE_TRIGGERED] — auto-silence threshold reached (manual/auto mode).
 * - [ABNORMAL_SILENCE] — abnormal silence detected (manual/manual mode).
 * - [ENGINE_ERROR] — internal pipeline error.
 *
 * ## Legacy codes (existing pipeline)
 * These codes are produced by the existing internal pipeline and are
 * mapped to the new codes by [ReturnCodeMapper] in the new wrapper path:
 *
 * - [OK] — mapped to [SUCCESS].
 * - [NO_SPEECH] — no speech detected (retained for direct pipeline use).
 * - [SILENCE_TIMEOUT] — mapped to [ABNORMAL_SILENCE].
 * - [UTTERANCE_TOO_LONG] — mapped to [MAX_DURATION_REACHED].
 * - [ERROR] — mapped to [ENGINE_ERROR].
 */
enum class SttReturnCode {
    // ── Legacy codes (existing pipeline) ────────────────────────────────
    OK,
    NO_SPEECH,
    SILENCE_TIMEOUT,
    UTTERANCE_TOO_LONG,
    ERROR,

    // ── New API codes (Phase 1+) ────────────────────────────────────────
    SUCCESS,
    CONFIG_NOT_SET,
    INVALID_CONFIG,
    MAX_DURATION_REACHED,
    AUTO_SILENCE_TRIGGERED,
    ABNORMAL_SILENCE,
    ENGINE_ERROR
}
