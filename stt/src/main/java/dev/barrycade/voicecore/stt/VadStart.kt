package dev.barrycade.voicecore.stt

/**
 * Configuration for [VadStart].
 *
 * @property vadStartThreshold Energy threshold that triggers start.
 *        VAD must report energy >= this value.
 * @property minSpeechMs Minimum consecutive speech duration (ms) before
 *        start is signalled. Prevents triggering on brief transient noise.
 */
internal data class VadStartConfig(
    val vadStartThreshold: Float,
    val minSpeechMs: Int
)

/**
 * [StartStrategy] that begins capture when VAD detects sustained speech.
 *
 * Capture starts only when:
 * 1. VAD is available.
 * 2. VAD energy >= [cfg.vadStartThreshold].
 * 3. VAD speech duration >= [cfg.minSpeechMs].
 *
 * @param cfg Configuration parameters.
 */
internal class VadStart(private val cfg: VadStartConfig) : StartStrategy {
    override fun shouldStart(events: SttEvents, vad: Vad?): Boolean {
        vad ?: return false

        return vad.lastFrameEnergy >= cfg.vadStartThreshold &&
            vad.speechDurationMs >= cfg.minSpeechMs
    }
}

