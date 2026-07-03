package dev.barrycade.voicecore.stt

/**
 * Pure RMS-energy voice activity detector for FloatArray audio frames.
 * VAD is energy-based only — no high-pass filter, no zero-crossing rate.
 * It performs pure math over the frame and does not interact with Whisper or audio capture.
 */
internal class Vad(
    private val energyThreshold: Double = 0.025
) {
    internal var debugLogging: Boolean = false

    internal var lastFrameEnergy: Float = 0f

    constructor(config: RuntimeSttConfig) : this(config.energyThreshold.toDouble())

    private fun debug(msg: String) {
        if (debugLogging) {
            println(msg)
        }
    }

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

        debug("VAD energy=$energy threshold=$energyThreshold")
        return energy >= energyThreshold.toFloat()
    }
}
