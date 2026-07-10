package dev.barrycade.voicecore.stt

/**
 * [CaptureStrategy] for the MANUAL_MANUAL lifecycle: start on explicit caller
 * request, stop on explicit caller request.
 *
 * ## Drain mode
 *
 * Uses [DrainMode.DRAIN_FROM_NEXT_FRAME] — the session buffer is cleared on
 * [begin], discarding any PCM that accumulated before the start signal. This
 * matches user expectation: "I pressed start, capture from now."
 *
 * ## Stop behaviour
 *
 * [onStopPressed] calls [CaptureManager.stopCapture] to halt the microphone
 * after the STT layer has extracted PCM via [CaptureManager.finalize].
 * Inference submission is handled by the STT layer.
 *
 * @see ManualManualSpecific Configuration data class for this strategy.
 */
internal class ManualManualStrategy : CaptureStrategy {

    override val drainMode = DrainMode.DRAIN_FROM_NEXT_FRAME

    override fun onStartPressed(sessionManager: SessionManager) {
        sessionManager.begin(drainMode)
    }

    override fun onStopPressed(sessionManager: SessionManager) {
        // Stop capture after STT has extracted PCM via finalize().
        // Inference submission handled by STT layer.
        sessionManager.stopCapture()
    }
}
