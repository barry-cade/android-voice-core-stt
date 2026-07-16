package dev.barrycade.voicecore.stt

/**
 * Interface for PCM session management.
 *
 * A session represents a single recording window: [begin] starts buffering
 * PCM frames, [finalize] returns the accumulated raw PCM, and [reset] clears
 * the session for reuse.
 *
 * Production: [CaptureManager].
 * Tests: [FakeCaptureManager].
 *
 * This interface exists to decouple [SpeechToText] from the concrete
 * [CaptureManager] implementation, enabling testability without Android
 * dependencies.
 */
internal interface SessionManager : AudioSource {

    /**
     * Begin a session: start buffering PCM frames into the session buffer.
     * Previous session data is cleared.
     *
     * @param mode [DrainMode] that determines how the initial PCM buffer
     *        is handled. See [DrainMode] for details.
     */
    fun begin(mode: DrainMode = DrainMode.DRAIN_FROM_NEXT_FRAME)

    /**
     * Start PCM capture synchronously. Capture must begin before any
     * frames can be buffered into the session.
     *
     * @return true if PCM capture started successfully, false on failure.
     */
    fun beginPcmCapture(): Boolean

    /**
     * Start STT processing (drain thread / processor hand-off).
     *
     * Temporary placeholder — delegates to [begin] for now.
     * Phase 2 will move drain thread logic into this method.
     */
    fun beginSttProcessing()

    /**
     * Mark PCM capture as active for STT processing without starting
     * the drain thread or VAD/accumulator.
     *
     * In ManualStart + ManualStop mode, the drain thread is not started.
     * This method marks the PCM stream as accepted (sets [sttActive] to true)
     * so that frames buffered by the processor via [pollFrame] are not
     * silently dropped by the drain-thread guard.
     *
     * In Auto mode, [beginSttProcessing] handles this automatically.
     */
    fun activatePcmCapture()

    /**
     * Finalize the session: return all accumulated PCM as raw concatenated
     * FloatArray. After this call, the session buffer is cleared.
     *
     * Returns an empty FloatArray if no frames were accumulated since [begin].
     */
    fun finalize(): FloatArray

    /**
     * Reset session state: clear the session buffer and queue.
     * Capture continues running — only the session data is discarded.
     */
    fun reset()

    /**
     * Shut down capture permanently. After this call, no new sessions
     */
    fun shutdown()

    /**
     * Restart the underlying audio capture after a prior [finalize] stopped it.
     *
     * Called to prepare for the next utterance.
     * After this call, [begin] can start a new session.
     *
     * Safe to call multiple times — idempotent if capture is already running.
     * Must NOT be called after [shutdown].
     *
     * @return true if capture was restarted successfully, false on failure.
     */
    fun restartCapture(): Boolean
}
    
