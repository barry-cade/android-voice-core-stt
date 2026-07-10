package dev.barrycade.voicecore.stt

/**
 * Strategy that defines how a capture session starts and stops.
 *
 * Every [CaptureStrategy] MUST explicitly declare its [drainMode] to ensure
 * the early-PCM behaviour is visible and deliberate.
 *
 * ## Implementations
 * - [ManualManualStrategy]: start/stop both explicit. Uses [DrainMode.DRAIN_FROM_NEXT_FRAME].
 * - Future: [AlwaysOnStrategy] (conceptual) would use [DrainMode.DRAIN_FROM_HEAD].
 *
 * @see DrainMode
 * @see CaptureManager.begin
 */
internal interface CaptureStrategy {

    /**
     * Drain mode for this strategy.
     *
     * Every strategy MUST declare its drain mode explicitly.
     * No defaults — the implementer must choose.
     */
    val drainMode: DrainMode

    /**
     * Called when the user (or trigger) presses "start".
     *
     * @param sessionManager The active [SessionManager] instance.
     */
    fun onStartPressed(sessionManager: SessionManager)

    /**
     * Called when the user (or trigger) presses "stop".
     *
     * @param sessionManager The active [SessionManager] instance.
     */
    fun onStopPressed(sessionManager: SessionManager)
}
