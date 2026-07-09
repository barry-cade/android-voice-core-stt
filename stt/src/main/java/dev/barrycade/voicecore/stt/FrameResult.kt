package dev.barrycade.voicecore.stt

/**
 * Sealed result type for [UtteranceAccumulator.processChunk].
 *
 * Replaces the previous FloatArray? return type + side channels
 * (terminationReason, timeoutFired, autoStopFired, AutoSilenceStopTrigger).
 *
 * Every frame produces exactly one [FrameResult] — the caller (ProcessorController)
 * switches on this type to determine the next action.
 */
internal sealed class FrameResult {

    /** Continue processing — no utterance has ended. */
    data object Continue : FrameResult()

    /** Utterance finalized normally (manual STOP or forced finalize). Transcribe. */
    data class NormalFinalize(val code: SttReturnCode, val pcm: FloatArray) : FrameResult()

    /** Utterance finalized by auto-silence trigger. Transcribe and stop. */
    data class AutoStop(val pcm: FloatArray) : FrameResult()

    /**
     * Utterance terminated abnormally (max duration or abnormal silence).
     * Do NOT call Whisper — the PCM has been discarded.
     * [code] is the [SttReturnCode] categorising the termination.
     */
    data class AbnormalTerminate(val code: SttReturnCode) : FrameResult()

    /**
     * Utterance terminated abnormally but PCM has been preserved for transcription.
     * The caller MUST run inference on [pcm] before dispatching the [code].
     *
     * Added to fix the bug where abnormal silence/timeout discarded the utterance
     * buffer, preventing any transcription from reaching the UI.
     */
    data class AbnormalTerminateWithPcm(val code: SttReturnCode, val pcm: FloatArray) : FrameResult()
}

