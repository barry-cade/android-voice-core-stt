package dev.barrycade.voicecore.stt

/**
 * Simple RMS-energy voice activity detector for FloatArray audio frames.
 * It performs pure math over the frame and does not interact with Whisper or audio capture.
 */
internal class Vad(
    private val energyThreshold: Double = 0.01
) {
    private val debugLogging = false
    constructor(config: RuntimeSttConfig) : this(config.energyThreshold.toDouble())
    fun isSpeech(frame: FloatArray): Boolean {
        if (frame.isEmpty()) return false

        var sumSquares = 0.0
        for (sample in frame) {
            val normalized = sample.toDouble()
            sumSquares += normalized * normalized
        }

        val rms = kotlin.math.sqrt(sumSquares / frame.size)
        val isSpeech = rms >= energyThreshold
        return isSpeech
    }
}
