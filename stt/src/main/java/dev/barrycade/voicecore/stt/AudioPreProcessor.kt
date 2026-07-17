package dev.barrycade.voicecore.stt

/**
 * Optional pre-processing stage for PCM audio frames.
 *
 * Applies noise resilience operations before frames reach the VAD:
 *
 * 1. **High-pass filter (HPF):** 1st-order IIR filters out low-frequency rumble
 *    (e.g. motor chassis vibration). Operates in-place with pre-allocated
 *    state — zero allocation on the hot path.
 * 2. **Zero-crossing rate (ZCR) rejection:** Computes the zero-crossing rate
 *    of the frame. High-ZCR signals with low low-frequency energy (e.g. servo
 *    whine) are flagged as noise, allowing the caller to treat them as silence.
 *
 * Both features are disabled by default (HPF cutoff = 0, ZCR = false).
 * Construction is cheap — the IIR coefficient is computed once.
 *
 * ## Thread safety
 *
 * Not thread-safe. Each capture pipeline should own one instance and call
 * [process] from a single thread.
 *
 * @param highPassCutoffHz Cutoff frequency in Hz (0 = disabled). Range [0, 2000].
 * @param zcrEnabled When true, ZCR rejection is active.
 * @param sampleRate Sample rate of the incoming audio (default 16000 for 16 kHz PCM).
 */
internal class AudioPreProcessor(
    private val highPassCutoffHz: Int = 0,
    private val zcrEnabled: Boolean = false,
    private val sampleRate: Int = 16000
) {
    // ── High-pass filter (IIR) state ─────────────────────────────────────
    // Pre-allocated; no allocations in the hot path.
    private var hpfLastX: Float = 0f
    private var hpfLastY: Float = 0f

    /**
     * IIR alpha coefficient, computed once at construction.
     * Standard 1st-order HPF: y[n] = alpha * (y[n-1] + x[n] - x[n-1])
     * alpha = RC / (RC + dt),  RC = 1 / (2 * pi * cutoff),  dt = 1 / sampleRate
     */
    private val hpfAlpha: Float = if (highPassCutoffHz > 0) {
        val dt = 1.0f / sampleRate
        val rc = 1.0f / (2.0f * kotlin.math.PI.toFloat() * highPassCutoffHz)
        rc / (rc + dt)
    } else {
        0f
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Pre-process a single PCM frame.
     *
     * 1. Applies high-pass filter in-place on [frame] (if enabled).
     * 2. Computes ZCR and returns true if the frame should be rejected as noise.
     *
     * @param frame PCM audio frame as FloatArray (values in [-1.0, 1.0]).
     *        Modified in-place when HPF is enabled.
     * @return true if the frame should be treated as silence (ZCR rejection).
     *         Returns false if the frame passes all checks.
     */
    fun process(frame: FloatArray): Boolean {
        if (highPassCutoffHz > 0) {
            applyHighPassInPlace(frame)
        }

        if (zcrEnabled && isNoiseByZcr(frame)) {
            return true
        }

        return false
    }

    /**
     * Reset the IIR filter state. Call between sessions to prevent filter
     * state from carrying over across unrelated audio streams.
     */
    fun reset() {
        hpfLastX = 0f
        hpfLastY = 0f
    }

    // ── High-pass filter — in-place ─────────────────────────────────────

    /**
     * 1st-order IIR high-pass filter, applied in-place.
     *
     * Transfer function: y[n] = alpha * (y[n-1] + x[n] - x[n-1])
     *
     * Zero allocations: the [frame] array is modified directly.
     */
    private fun applyHighPassInPlace(frame: FloatArray) {
        for (i in frame.indices) {
            val x = frame[i]
            val y = hpfAlpha * (hpfLastY + x - hpfLastX)
            frame[i] = y
            hpfLastX = x
            hpfLastY = y
        }
    }

    // ── Zero-crossing rate ──────────────────────────────────────────────

    /**
     * Returns true if the frame's ZCR exceeds the noise threshold.
     *
     * Human speech typically has ZCR in the range 5–15% at 16 kHz.
     * High-frequency noise (servo whine, static) can exceed 30–50%.
     *
     * A simple threshold of 0.3 (30%) is used. This is deliberately
     * conservative to avoid rejecting sibilants ("s", "sh") which have
     * higher ZCR than vowels but are still speech.
     *
     * Optimisation note: uses a raw loop for zero-allocation scanning.
     */
    private fun isNoiseByZcr(frame: FloatArray): Boolean {
        if (frame.size < 2) return false

        var zeroCrossings = 0
        for (i in 1 until frame.size) {
            // Sign change: previous sample positive, current negative (or vice versa)
            if ((frame[i - 1] >= 0f && frame[i] < 0f) ||
                (frame[i - 1] < 0f && frame[i] >= 0f)
            ) {
                zeroCrossings++
            }
        }

        val zcr = zeroCrossings.toFloat() / (frame.size - 1)
        return zcr > ZCR_THRESHOLD
    }

    companion object {
        /**
         * ZCR threshold: frames with a zero-crossing rate above this
         * value are considered noise and rejected.
         *
         * 0.3 = 30% — conservative enough to pass sibilants but catch
         * sustained high-frequency whine.
         */
        private const val ZCR_THRESHOLD = 0.3f
    }
}
