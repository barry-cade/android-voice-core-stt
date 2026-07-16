package dev.barrycade.voicecore.stt

/**
 * Interface for microphone PCM frame sourcing.
 *
 * Production: [CaptureManager] wraps [AudioCapture] (Android AudioRecord).
 * Tests: [FakeCaptureController] provides deterministic frames.
 *
 * Only the three methods used by [ProcessorController] polling loop
 * are exposed — start, poll, stop. [clearQueue] is used by CaptureManager
 * to discard frames during reset.
 */
internal interface AudioSource {
    /**
     * Start the audio source. Returns true on success.
     * Frames become available via [pollFrame].
     */
    fun startCapture(): Boolean

    /**
     * Stop the audio source and release resources.
     */
    fun stopCapture()

    /**
     * Poll the next PCM frame, or null if none available.
     */
    fun pollFrame(): FloatArray?

    /**
     * Poll the next PCM frame WITHOUT appending it to the session buffer.
     *
     * Used by [MinimalPollingController] when VAD gating is active.
     * Default implementation delegates to [pollFrame] for consumers that
     * do not separate poll from append (e.g. [ProcessorController]).
     */
    fun pollFrameWithoutAppend(): FloatArray? = pollFrame()

    /**
     * Append a pre-polled PCM frame to the session buffer.
     *
     * Used together with [pollFrameWithoutAppend] to conditionally
     * accumulate frames after VAD gating.
     * Default is a no-op for consumers that don't use the split pattern.
     */
    fun appendFrameToSession(frame: FloatArray) { /* no-op by default */ }

    /**
     * Discard all pending frames from the internal queue.
     * Called before starting the processor to clear frames accumulated
     * during warm-up (ambient noise, not intentional speech).
     */
    fun clearQueue()
}
