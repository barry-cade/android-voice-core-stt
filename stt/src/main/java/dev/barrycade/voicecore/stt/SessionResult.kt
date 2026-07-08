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
     *
     * @property code Always [SttReturnCode.OK] for successful transcription.
     */
    data class Transcribe(val pcm: FloatArray, val code: SttReturnCode = SttReturnCode.OK) : SessionResult()

    /**
     * Non-transcription outcome — do NOT call Whisper.
     *
     * Produced by:
     * - Abnormal silence (manual/manual mode) → [SttReturnCode.SILENCE_TIMEOUT]
     * - Max duration exceeded (both modes) → [SttReturnCode.UTTERANCE_TOO_LONG]
     *
     * @property code The [SttReturnCode] categorising this outcome.
     */
    data class Reason(val code: SttReturnCode) : SessionResult()
}
