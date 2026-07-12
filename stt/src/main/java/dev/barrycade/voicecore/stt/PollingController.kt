package dev.barrycade.voicecore.stt

/**
 * Common interface for PCM frame polling controllers.
 *
 * Two implementations:
 * - [ProcessorController]: full STT pipeline with VAD, accumulator, utterance lifecycle.
 * - [MinimalPollingController]: minimal frame poller for ManualStart + ManualStop mode.
 */
internal interface PollingController {

    /**
     * Start the polling loop on a background thread.
     */
    fun start()

    /**
     * Stop the polling loop and join the background thread.
     * Idempotent: multiple calls are safe after the thread has stopped.
     */
    fun stop()

    /** Accumulated VAD active time in milliseconds. 0 if VAD is not used. */
    val vadActiveMs: Long

    /** Last utterance duration in milliseconds. 0 if accumulator is not used. */
    val lastUtteranceDurationMs: Int

    /** VAD confidence for diagnostic use. 0 if VAD is not used. */
    val vadConfidence: Float

    /** RMS sampler for diagnostic logging. */
    val rmsSampler: RmsSampler

    /**
     * Reset per-utterance VAD active time to 0.
     * No-op if VAD is not used.
     */
    fun resetVadActiveMs()

    /**
     * Drain remaining frames from [AudioSource] after the polling loop has stopped.
     * Returns finalized PCM if available, null otherwise.
     */
    fun drainRemainingFrames(): FloatArray?

    /**
     * Finalise the current utterance and return the PCM buffer.
     * Returns null if no PCM was accumulated.
     */
    fun stopAndFinalize(): FloatArray?
}
