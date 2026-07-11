package dev.barrycade.voicecore.stt

/**
 * [StopStrategy] that ends capture after a fixed maximum duration.
 *
 * Ignores VAD and [SttEvents] — purely time-based.
 *
 * @param maxDurationMs Maximum session duration in milliseconds.
 */
internal class DurationStop(private val maxDurationMs: Int) : StopStrategy {
    override fun shouldStop(events: SttEvents, vad: Vad?, elapsedMs: Int): Boolean {
        return elapsedMs >= maxDurationMs
    }
}
