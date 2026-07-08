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
    data class NormalFinalize(val pcm: FloatArray) : FrameResult()

    /** Utterance finalized by auto-silence trigger. Transcribe and stop. */
    data class AutoStop(val pcm: FloatArray) : FrameResult()

    /**
     * Utterance terminated abnormally (max duration or abnormal silence).
     * [reason] is the user-facing message. Do NOT call Whisper.
     * [code] is the [SttReturnCode] categorising the termination.
     */
    data class AbnormalTerminate(val reason: String, val code: SttReturnCode = SttReturnCode.ERROR) : FrameResult()
}
