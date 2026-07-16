package dev.barrycade.voicecore.stt

import kotlin.math.sqrt

/**
 * Lightweight energy-based VAD gate for manual mode.
 *
 * Unlike [Vad] (which has hysteresis, stateful duration tracking, and
 * confidence scoring), this gate is purely stateless: it calculates RMS
 * energy per frame and returns true if the energy is at or above the
 * configurable threshold.
 *
 * No hysteresis — the same [energyThreshold] is used for every frame.
 * No frame tracking — no [speechDurationMs], [silenceMs], or [vadConfidence].
 * No state — can be shared across frames without reset between sessions.
 *
 * @param energyThreshold RMS energy threshold below which a frame is
 *        classified as silence. Default 0.005f matches [Vad]'s default.
 */
internal class VadGate(private val energyThreshold: Float = 0.005f) {

    /**
     * Returns true if [frame] contains speech-level energy (RMS >= [energyThreshold]).
     * Returns false for empty frames and frames below the threshold.
     */
    fun isSpeech(frame: FloatArray): Boolean {
        if (frame.isEmpty()) return false
        var sumSquares = 0.0
        for (sample in frame) {
            val n = sample.toDouble()
            sumSquares += n * n
        }
        val rms = sqrt(sumSquares / frame.size)
        return rms >= energyThreshold
    }
}
