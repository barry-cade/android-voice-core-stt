package dev.barrycade.voicecore.stt

/**
 * Lightweight periodic RMS sampler for diagnostic logging.
 * Records average RMS, peak RMS, and a running noise floor estimate.
 *
 * Sampling rate: every [samplingIntervalMs] milliseconds (default ~200ms).
 * Thread-safe: all state is @Volatile or protected by the caller's synchronization.
 * Non-blocking: pure math, no allocations per sample beyond the running counters.
 *
 * When [debugLogging] is enabled, calls [onSample] with each sample output.
 */
internal class RmsSampler(
    sampleRate: Int = 16000,
    samplingIntervalMs: Int = 200,
    private val debugLogging: Boolean = false,
    private val onSample: ((avg: Float, peak: Float, floor: Float) -> Unit)? = null
) {
    // ── Accumulators for current sampling window ─────────────────────────
    private var frameCounter: Int = 0
    private var sumSquares: Double = 0.0
    private var peakRmsInWindow: Float = 0f
    private val framesPerWindow: Int = (sampleRate * samplingIntervalMs / 1000) / 320 // 320 = frames of 20ms

    // ── Smoothed noise floor estimate ────────────────────────────────────
    private var currentNoiseFloor: Float = 0.001f

    // ── Exposed diagnostics ──────────────────────────────────────────────
    @Volatile
    internal var avgRms: Float = 0f
        private set

    @Volatile
    internal var peakRms: Float = 0f
        private set

    @Volatile
    internal var noiseFloorRms: Float = 0.001f
        private set

    /**
     * Feed a frame into the sampler. Call from the processing thread
     * for every frame. Sampling interval is handled internally.
     */
    internal fun feedFrame(frame: FloatArray) {
        if (frame.isEmpty()) return

        var frameSumSq = 0.0
        for (sample in frame) {
            frameSumSq += sample.toDouble() * sample.toDouble()
        }
        val frameRms = kotlin.math.sqrt(frameSumSq / frame.size).toFloat()

        // Accumulate for window
        frameCounter += 1
        sumSquares += frameSumSq
        if (frameRms > peakRmsInWindow) {
            peakRmsInWindow = frameRms
        }

        // Update noise floor on low-energy frames
        if (frameRms < currentNoiseFloor * 1.5f) {
            currentNoiseFloor = currentNoiseFloor * 0.9f + frameRms * 0.1f
        } else {
            currentNoiseFloor *= 0.99f // slow decay
        }

        // Check if window is complete
        if (frameCounter >= framesPerWindow) {
            // Compute averages
            val avg = kotlin.math.sqrt(sumSquares / (frameCounter * frame.size.toDouble())).toFloat()
            avgRms = avg
            peakRms = peakRmsInWindow
            noiseFloorRms = currentNoiseFloor

            if (debugLogging) {
                onSample?.invoke(avgRms, peakRms, noiseFloorRms)
            }

            // Reset window
            frameCounter = 0
            sumSquares = 0.0
            peakRmsInWindow = 0f
        }
    }

    /**
     * Reset all state. Call when pipeline starts or resets.
     */
    internal fun reset() {
        frameCounter = 0
        sumSquares = 0.0
        peakRmsInWindow = 0f
        currentNoiseFloor = 0.001f
        avgRms = 0f
        peakRms = 0f
        noiseFloorRms = 0.001f
    }
}
