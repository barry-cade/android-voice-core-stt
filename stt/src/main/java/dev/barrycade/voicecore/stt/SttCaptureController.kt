package dev.barrycade.voicecore.stt

/**
 * Owns the PCM capture lifecycle: start, finalise, reset, shutdown.
 *
 * ## Thread ownership
 *
 * All public methods are called from the [SpeechToText] caller thread,
 * serialized via [SpeechToText.stateLock]. The [sessionManager] field
 * is read from worker threads (processor/drain) — it is assigned once
 * in the constructor (or once via [SttCaptureController] replacement

 * in [SpeechToText.init]) and never modified afterward, so
 * [@Volatile] is not required.
 *
 * Responsibilities:
 * - Encapsulate [SessionManager] behind a stable, narrow API surface.
 * - Provide clean capture-lifecycle methods for [SpeechToText]:
 *   [startCapture], [finaliseAndStop], [resetForNextSession], [shutdown].
 * - Expose [sessionManager] for injection into processing components
 *   (mode controller, processor controller) that need [AudioSource] or [SessionManager].
 *
 * No VAD, no utterance accumulation, no lifecycle state machine.
 * Only raw PCM capture start/stop/buffer management.
 *
 * Lifecycle:
 * Construct -> startCapture() -> [Active] -> finaliseAndStop() -> [Stopped]
 *                                            resetForNextSession() -> [Ready]
 *                                            shutdown() -> [Terminal]
 *
 * @param sessionManager The underlying [SessionManager] implementation.
 *        Immutable after construction — may be replaced in [SpeechToText.initStt]
 *        when runtime buffer size requires reconstruction.
 */
internal class SttCaptureController(
    val sessionManager: SessionManager
) {

    /**
     * Start a capture session: begin PCM capture, then start STT processing
     * (drain thread or processor hand-off).
     *
     * In Manual mode, only PCM capture is started. STT processing is
     * activated separately via [activatePcmCapture].
     *
     * @param manualMode When true, only [beginPcmCapture] is called.
     *        When false, [beginSttProcessing] is also called.
     * @return true if capture started successfully, false on failure.
     */
    fun startCapture(manualMode: Boolean): Boolean {
        val pcmStarted = sessionManager.beginPcmCapture()
        if (!pcmStarted) return false
        if (!manualMode) {
            sessionManager.beginSttProcessing()
        }
        return true
    }

    /**
     * Activate PCM capture for Manual mode without starting the drain thread.
     *
     * Called in ManualStart + ManualStop mode after [startCapture] to mark
     * the PCM stream as accepted by the system.
     */
    fun activatePcmCapture() {
        sessionManager.activatePcmCapture()
    }

    /**
     * Finalise the session and stop capture.
     *
     * Drains remaining PCM from the queue, stops AudioCapture, and
     * returns all accumulated raw PCM samples. After this call,
     * [resetForNextSession] must be called before a new session.
     *
     * If a [vadGate] is provided, only frames with speech-level energy
     * are accumulated during the final drain. This prevents ambient
     * silence enqueued after the last poll from contaminating the buffer.
     *
     * @param vadGate Optional VAD gate for energy-based frame filtering
     *        during the final drain. Typically provided in manual mode.
     * @return All raw PCM samples accumulated since [startCapture].
     */
    fun finaliseAndStop(vadGate: VadGate? = null): FloatArray {
        val pcm = sessionManager.finalize(vadGate)
        sessionManager.stopCapture()
        return pcm
    }

    /**
     * Reset capture state for a new session.
     *
     * Restarts AudioCapture (if stopped), then resets the session buffer
     * and queue. Capture continues running after this call.
     */
    fun resetForNextSession() {
        sessionManager.restartCapture()
        sessionManager.reset()
    }

    /**
     * Shut down capture permanently. Terminal state.
     *
     * After this call, no new sessions can be started.
     */
    fun shutdown() {
        sessionManager.shutdown()
    }
}
