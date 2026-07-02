package dev.barrycade.voicecore.stt

/**
 * Simple RMS-energy voice activity detector for FloatArray audio frames.
 * It performs pure math over the frame and does not interact with Whisper or audio capture.
 */
internal class Vad(
    private val energyThreshold: Double = 0.01
) {
    private val debugLogging = false

    internal var highPassEnabled: Boolean = false
    internal var highPassCutoffHz: Int = 200
    internal var zeroCrossingEnabled: Boolean = false

    internal var lastFrameEnergy: Float = 0f
    internal var lastZeroCrossingRate: Int = 0
    internal var lastHighPassApplied: Boolean = false

    constructor(config: RuntimeSttConfig) : this(config.energyThreshold.toDouble())

    private fun debug(msg: String) {
        if (debugLogging) {
            println(msg)
        }
    }

    private fun applyHighPassFilter(frame: FloatArray, cutoffHz: Int) {
        // Placeholder: no-op for now
    }

    private fun computeZeroCrossingRate(frame: FloatArray): Int {
        // Placeholder: return 0 for now
        return 0
    }

    fun isSpeech(frame: FloatArray): Boolean {
        if (frame.isEmpty()) return false

        if (highPassEnabled) {
            applyHighPassFilter(frame, highPassCutoffHz)
        }

        if (zeroCrossingEnabled) {
            val zcr = computeZeroCrossingRate(frame)
            // Step 9 will use this value
        }

        var sumSquares = 0.0
        for (sample in frame) {
            val normalized = sample.toDouble()
            sumSquares += normalized * normalized
        }

        val rms = kotlin.math.sqrt(sumSquares / frame.size)
        val energy = rms.toFloat()
        lastFrameEnergy = energy
        lastZeroCrossingRate = if (zeroCrossingEnabled) {
            computeZeroCrossingRate(frame)
        } else {
            0
        }
        lastHighPassApplied = highPassEnabled
        debug("VAD metrics: energy=$lastFrameEnergy zcr=$lastZeroCrossingRate hp=$lastHighPassApplied")
        val isSpeech = energy >= energyThreshold.toFloat()
        return isSpeech
    }
}
