package dev.barrycade.voicecore.stt

/**
 * Pure RMS-energy voice activity detector for FloatArray audio frames.
 * VAD is energy-based only — no high-pass filter, no zero-crossing rate.
 * It performs pure math over the frame and does not interact with Whisper or audio capture.
 *
 * Hysteresis: once speech is detected, the threshold drops by 30% to prevent
 * rapid on/off flickering at the edges of speech segments.
 */
internal class Vad(
    private val energyThreshold: Double = 0.005
) {
    internal var debugLogging: Boolean = false

    internal var lastFrameEnergy: Float = 0f
    private var isCurrentlySpeech: Boolean = false

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
        isCurrentlySpeech = speech
        return speech
    }
}
