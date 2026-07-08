package dev.barrycade.voicecore.stt

/**
 * Closed enumeration of all possible STT session return codes.
 *
 * Every completed session produces exactly one [SttReturnCode] that
 * categorises the outcome. The caller is responsible for interpreting
 * the code and presenting any user-facing messages.
 *
 * Values:
 * - [OK] — utterance was transcribed successfully.
 * - [NO_SPEECH] — no speech detected during the session.
 * - [SILENCE_TIMEOUT] — silence threshold exceeded (manual/manual mode).
 * - [UTTERANCE_TOO_LONG] — max utterance duration exceeded.
 * - [ERROR] — internal pipeline error.
 */
enum class SttReturnCode {
    OK,
    NO_SPEECH,
    SILENCE_TIMEOUT,
    UTTERANCE_TOO_LONG,
    ERROR
}
