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
 * - [INVALID_CONFIG] — config validation failed.
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
internal enum class SttReturnCode {
    // ── Legacy codes (existing pipeline) ────────────────────────────────

    /** Utterance transcribed successfully. Maps to [SUCCESS]. */
    OK,

    /** No speech detected during the session. Maps to [SUCCESS] (transcript is null). */
    NO_SPEECH,

    /** Silence threshold exceeded in MANUAL_MANUAL mode. Maps to [ABNORMAL_SILENCE]. */
    SILENCE_TIMEOUT,

    /** Max utterance duration exceeded. Maps to [MAX_DURATION_REACHED]. */
    UTTERANCE_TOO_LONG,

    /** Internal pipeline error. Maps to [ENGINE_ERROR]. */
    ERROR,

    // ── New API codes (Phase 1+) ────────────────────────────────────────

    /** Utterance transcribed successfully. Transcript is available in [SessionResult.transcript]. */
    SUCCESS,

    /** [SpeechToText.setConfig] was not called before [SpeechToText.startSession]. */
    CONFIG_NOT_SET,

    /** Config validation failed. */
    INVALID_CONFIG,

    /** Maximum utterance duration was exceeded. */
    MAX_DURATION_REACHED,

    /** Auto-silence threshold reached in MANUAL_AUTO mode. */
    AUTO_SILENCE_TRIGGERED,

    /** Abnormal silence detected in MANUAL_MANUAL mode. */
    ABNORMAL_SILENCE,

    /** Internal pipeline error occurred during the session. */
    ENGINE_ERROR
}
