package dev.barrycade.voicecore.stt

/**
 * Configuration for [WakeWordStart].
 *
 * @property wakeWord The wake word phrase to detect (e.g. "Hey Computer").
 * @property confidenceThreshold Minimum detection confidence [0.0, 1.0].
 */
internal data class WakeWordConfig(
    val wakeWord: String,
    val confidenceThreshold: Float
)

/**
 * [StartStrategy] that begins capture when a wake word is detected.
 *
 * Placeholder — real wake-word detection requires a separate model
 * and inference pipeline. Currently delegates to [SttEvents.wakeWordDetected].
 *
 * @param cfg Configuration parameters (for future use).
 */
internal class WakeWordStart(private val cfg: WakeWordConfig) : StartStrategy {
    override fun shouldStart(events: SttEvents, vad: Vad?): Boolean {
        return events.wakeWordDetected.consume()
    }
}
