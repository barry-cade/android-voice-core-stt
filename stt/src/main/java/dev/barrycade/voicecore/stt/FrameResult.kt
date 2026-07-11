package dev.barrycade.voicecore.stt

/**
 * Sealed result type for [UtteranceAccumulator.processChunk].
 *
 * The accumulator produces exactly two variants:
 * - [Continue]: keep processing, utterance in progress
 * - [UtteranceReady]: a complete utterance buffer is ready for transcription
 *
 * Capture (session) boundaries are the exclusive responsibility of [StopStrategy].
 * The accumulator never drives capture stop.
 */
internal sealed class FrameResult {

    /** Continue processing — no utterance has ended. */
    data object Continue : FrameResult()

    /**
     * Utterance is ready for transcription.
     * The caller should transcribe the PCM but NOT stop the session —
     * [StopStrategy] decides capture boundaries.
     */
    data class UtteranceReady(val pcm: FloatArray) : FrameResult()
}

