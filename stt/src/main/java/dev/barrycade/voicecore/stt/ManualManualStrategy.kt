package dev.barrycade.voicecore.stt

/**
 * [CaptureStrategy] for the MANUAL_MANUAL lifecycle: start on explicit caller
 * request, stop on explicit caller request.
 *
 * ## Drain mode
 *
 * Uses [ManualManualSpecific.drainMode] — the session buffer is cleared on
 * [begin] and the drain behaviour is determined by the config value.
 * Default is [DrainMode.DRAIN_FROM_NEXT_FRAME] (start fresh).
 *
 * ## Stop behaviour
 *
 * [onStopPressed] calls [CaptureManager.finalize] to collect accumulated PCM,
 * then stops capture. Inference submission is handled by the STT layer.
 *
 * @param config [ManualManualSpecific] with [energyThreshold], [maxDurationMs],
 *        [abnormalSilenceMs], and [drainMode].
 * @see ManualManualSpecific Configuration data class for this strategy.
 */
internal class ManualManualStrategy(
    private val config: ManualManualSpecific
) : CaptureStrategy {

    override val drainMode = config.drainMode

    override fun onStartPressed(sessionManager: SessionManager) {
        sessionManager.begin(drainMode)
    }

    override fun onStopPressed(sessionManager: SessionManager) {
        // Finalize PCM and stop capture — inference handled by STT layer.
        sessionManager.finalize()
        sessionManager.stopCapture()
    }
}
