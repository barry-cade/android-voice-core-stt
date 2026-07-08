package dev.barrycade.voicecore.stt

/**
 * Sealed result type for a completed STT session.
 *
 * Used by [SpeechToText.shutdownPipeline] to unify the three cleanup paths
 * (manual STOP, auto-silence, abnormal termination) into a single method.
 *
 * @see SpeechToText.shutdownPipeline
 */
internal sealed class SessionResult {

    /**
     * PCM data ready for Whisper transcription.
     *
     * Produced by:
     * - Manual STOP → finalize utterance → transcribe
     * - Auto-silence → finalize utterance → transcribe
     */
    data class Transcribe(val pcm: FloatArray) : SessionResult()

    /**
     * User-facing reason string — do NOT call Whisper.
     *
     * Produced by:
     * - Abnormal silence (manual/manual mode) → [ReasonMessages.abnormalSilence]
     * - Max duration exceeded (both modes) → [ReasonMessages.tooLong]
     */
    data class Reason(val message: String) : SessionResult()
}
