package dev.barrycade.voicecore.stt

/**
 * Owns session-level timing and PCM buffer lifecycle.
 *
 * Responsibilities:
 * - Track session start/end timestamps.
 * - Track inference start/end timestamps.
 * - Track PCM start time and accumulated PCM duration.
 * - Provide timing reset for new utterances.
 * - Provide elapsedMs computation.
 *
 * No lifecycle state, no mode branching, no callbacks — only timing and buffer metadata.
 */
internal class SttSessionController {

    /** Session start wall time (ms), set by [beginSession]. 0 when no session active. */
    var sessionStartMs: Long = 0L
        private set

    /** Inference start wall time (ms), set by [beginInference]. 0 when no inference active. */
    var inferenceStartMs: Long = 0L
        private set

    /** Inference end wall time (ms), set by [endInference]. 0 when no inference has completed. */
    var inferenceEndMs: Long = 0L
        private set

    // ── PCM timing fields (resettable per utterance) ──────────────────────

    /** PCM capture start wall time (ms), set when the processor starts. */
    var timingPcmStartMs: Long = 0L
        private set

    /** Total PCM capture duration (ms) accumulated since last reset. */
    var timingPcmTotalMs: Long = 0L
        private set

    /** Utterance start wall time (ms) — tracks pipeline start for timing snapshots. */
    var timingUtteranceStartMs: Long = 0L
        private set

    /**
     * Begin a new session: record session start timestamp.
     * Resets utterance-level timing fields.
     */
    fun beginSession() {
        sessionStartMs = System.currentTimeMillis()
        resetUtteranceTiming()
        SttLogger.lifecycle("SttSessionController: beginSession()")
    }

    /**
     * End the current session. Returns elapsed wall time in ms since [beginSession].
     * sessionStartMs is NOT reset — [resetSession] must be called for that.
     *
     * @return Elapsed ms since session start, or 0 if session was never started.
     */
    fun endSession(): Long {
        if (sessionStartMs > 0) {
            return System.currentTimeMillis() - sessionStartMs
        }
        return 0L
    }

    /**
     * Reset session state for a new utterance.
     * sessionStartMs is cleared.
     */
    fun resetSession() {
        sessionStartMs = 0L
        resetUtteranceTiming()
        SttLogger.lifecycle("SttSessionController: resetSession()")
    }

    /**
     * Begin inference: record inference start timestamp.
     */
    fun beginInference() {
        inferenceStartMs = System.currentTimeMillis()
    }

    /**
     * End inference: record inference end timestamp and compute duration.
     *
     * @return Inference duration in ms, or 0 if inference was never started.
     */
    fun endInference(): Long {
        inferenceEndMs = System.currentTimeMillis()
        if (inferenceStartMs > 0) {
            return inferenceEndMs - inferenceStartMs
        }
        return 0L
    }

    /**
     * Begin PCM capture timing. Records the wall time when the processor starts.
     */
    fun beginPcmTiming() {
        timingPcmStartMs = System.currentTimeMillis()
        SttLogger.pcm("[TIMING] PCM capture start")
    }

    /**
     * End PCM capture timing. Computes total PCM capture duration.
     *
     * @return Total PCM capture duration in ms, or 0 if PCM timing was never started.
     */
    fun endPcmTiming(): Long {
        if (timingPcmStartMs > 0) {
            timingPcmTotalMs = System.currentTimeMillis() - timingPcmStartMs
            return timingPcmTotalMs
        }
        return 0L
    }

    /**
     * Record the utterance start wall time.
     * Called when the processor loop begins.
     */
    fun beginUtteranceTiming() {
        timingUtteranceStartMs = System.currentTimeMillis()
    }

    /**
     * Get elapsed ms since utterance start, used for timing snapshots.
     *
     * @return Elapsed ms since utterance start, or current time if never started.
     */
    fun utteranceElapsedMs(): Long {
        if (timingUtteranceStartMs > 0) return timingUtteranceStartMs
        return System.currentTimeMillis()
    }

    /**
     * Get the PCM capture duration in ms captured during [endPcmTiming].
     */
    fun captureMs(): Long = timingPcmTotalMs

    /**
     * Reset all utterance-level timing fields.
     */
    fun resetUtteranceTiming() {
        timingPcmStartMs = 0L
        timingPcmTotalMs = 0L
        timingUtteranceStartMs = 0L
        inferenceStartMs = 0L
        inferenceEndMs = 0L
    }
}
