package dev.barrycade.voicecore.stt

/**
 * [StopStrategy] that ends capture on explicit caller request.
 *
 * Delegates to [SttEvents.manualStopPressed] — the caller raises the
 * event, and this strategy consumes it.
 *
 * @see ManualStart The corresponding start strategy.
 */
internal class ManualStop : StopStrategy {
    override fun shouldStop(events: SttEvents, vad: Vad?, elapsedMs: Int): Boolean {
        return events.manualStopPressed.consume()
    }
}
