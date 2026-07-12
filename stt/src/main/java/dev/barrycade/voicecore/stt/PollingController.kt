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

    /**
     * Returns true when this controller has an active VAD and can
     * expose meaningful VAD metrics. Returns false in Manual mode
     * (ManualStart + ManualStop) where VAD is not used.
     */
    fun supportsVadMetrics(): Boolean

    /**
     * Accumulated VAD active time in milliseconds.
     * null if VAD is not used (Manual mode).
     */
    val vadActiveMs: Long?

    /**
     * Last utterance duration in milliseconds.
     * null if accumulator is not used (Manual mode).
     */
    val lastUtteranceDurationMs: Int?

    /**
     * VAD confidence for diagnostic use.
     * null if VAD is not used (Manual mode).
     */
    val vadConfidence: Float?

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
