package dev.barrycade.voicecore.stt

/**
 * Pure RMS-energy voice activity detector for FloatArray audio frames.
 * VAD is energy-based only — no high-pass filter, no zero-crossing rate.
 * It performs pure math over the frame and does not interact with Whisper or audio capture.
 *
 * Hysteresis: once speech is detected, the threshold drops by 30% to prevent
 * rapid on/off flickering at the edges of speech segments.
 *
 * Diagnostic output:
 *   - [vadConfidence]: Float in 0.0..1.0 derived from energy-to-threshold proximity
 *     and consecutive speech frame count. Diagnostic only — does not affect VAD decisions.
 */
internal class Vad(
    private val energyThreshold: Double = 0.005
) {
    internal var debugLogging: Boolean = false

    internal var lastFrameEnergy: Float = 0f
    private var isCurrentlySpeech: Boolean = false

    /** Consecutive speech frames since last silence transition. */
    private var consecutiveSpeechFrames: Int = 0

    /**
     * VAD confidence in [0.0, 1.0].
     * High values indicate strong, sustained speech above threshold.
     * Low values indicate marginal energy or recent silence.
     * Diagnostic only — does not affect isSpeech() decisions.
     */
    @Volatile
    internal var vadConfidence: Float = 0f
        private set

    constructor(config: RuntimeSttConfig) : this(config.energyThreshold.toDouble())

    fun isSpeech(frame: FloatArray): Boolean {
        if (frame.isEmpty()) return false

        var sumSquares = 0.0
        for (sample in frame) {
            val normalized = sample.toDouble()
            sumSquares += normalized * normalized
        }

        val rms = kotlin.math.sqrt(sumSquares / frame.size)
        val energy = rms.toFloat()
        lastFrameEnergy = energy

        val activeThreshold = if (isCurrentlySpeech) {
            energyThreshold * 0.7  // lower threshold once in speech (hysteresis)
        } else {
            energyThreshold
        }

        val speech = energy >= activeThreshold.toFloat()

        // ── Update confidence (diagnostic only) ──────────────────────────
        if (speech) {
            consecutiveSpeechFrames += 1

            // Energy proximity: how far above threshold (capped at 2x threshold → 1.0)
            val ratio = if (activeThreshold > 0.0) {
                (energy / activeThreshold.toFloat()).coerceAtMost(2.0f)
            } else {
                1.0f
            }
            val energyConfidence = (ratio / 2.0f).coerceIn(0.0f, 1.0f)

            // Frame count bonus: consecutive speech frames increase confidence
            val frameBonus = (consecutiveSpeechFrames.toFloat() / 50.0f).coerceIn(0.0f, 0.3f)

            vadConfidence = (energyConfidence + frameBonus).coerceIn(0.0f, 1.0f)
        } else {
            consecutiveSpeechFrames = 0

            // Decay confidence quickly when silent
            vadConfidence = (vadConfidence * 0.5f).coerceIn(0.0f, 1.0f)
        }

        isCurrentlySpeech = speech
        return speech
    }
}
