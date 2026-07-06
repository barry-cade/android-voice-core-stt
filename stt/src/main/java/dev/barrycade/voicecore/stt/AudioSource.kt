package dev.barrycade.voicecore.stt

/**
 * Interface for microphone PCM frame sourcing.
 *
 * Production: [CaptureController] wraps [AudioCapture] (Android AudioRecord).
 * Tests: [FakeCaptureController] provides deterministic frames.
 *
 * Only the three methods used by [ProcessorController] polling loop
 * are exposed — start, poll, stop.
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
}
