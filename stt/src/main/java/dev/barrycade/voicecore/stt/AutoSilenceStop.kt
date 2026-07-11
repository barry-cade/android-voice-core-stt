package dev.barrycade.voicecore.stt

/**
 * Configuration for [AutoSilenceStop].
 *
 * @property silenceMs Consecutive silence duration (ms) that triggers stop.
 *        Measured from the last speech frame.
 * @property maxDurationMs Maximum allowed session duration (ms).
 *        If exceeded, the session stops regardless of silence state.
 */
internal data class AutoSilenceConfig(
    val silenceMs: Int,
    val maxDurationMs: Int
)

/**
 * [StopStrategy] that ends capture on sustained silence or max duration.
 *
 * Stops when:
 * 1. VAD silence duration >= [cfg.silenceMs], OR
 * 2. [elapsedMs] >= [cfg.maxDurationMs].
 *
 * Ignores [SttEvents] — fully automatic.
 *
 * @param cfg Configuration parameters.
 */
internal class AutoSilenceStop(internal val cfg: AutoSilenceConfig) : StopStrategy {
    override fun shouldStop(events: SttEvents, vad: Vad?, elapsedMs: Int): Boolean {
        if (elapsedMs >= cfg.maxDurationMs) return true
        if (vad != null && vad.silenceMs >= cfg.silenceMs) return true
        return false
    }
}

