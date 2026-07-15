package dev.barrycade.voicecore.stt

/**
 * Closed enumeration of all possible STT session return codes.
 *
 * Every completed session produces exactly one [SttReturnCode] that
 * categorises the outcome. The caller is responsible for interpreting
 * the code and presenting any user-facing messages.
 *
 * - [SUCCESS] — utterance was transcribed successfully.
 * - [CONFIG_NOT_SET] — [SpeechToText.setConfig] was not called before [startSession].
 * - [INVALID_CONFIG] — config validation failed.
 * - [MAX_DURATION_REACHED] — max utterance duration exceeded.
 * - [AUTO_SILENCE_TRIGGERED] — auto-silence threshold reached (manual/auto mode).
 * - [ABNORMAL_SILENCE] — abnormal silence detected (manual/manual mode).
 * - [ENGINE_ERROR] — internal pipeline error.
 */
internal enum class SttReturnCode {
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

