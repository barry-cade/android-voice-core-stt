package dev.barrycade.voicecore.stt

/**
 * Owns session-level timing and PCM buffer lifecycle.
 *
 * ## Thread ownership
 *
 * | Thread | Owns | Notes |
 * |--------|------|-------|
 * | Caller thread (SpeechToText) | All public methods | Serialized via [SpeechToText.stateLock] |
 * | Worker threads | Read-only access via [captureMs], [hasPcmTimingStarted], [currentPcmElapsedMs] | Guarded by internal [lock] for writes; reads may be from worker context for diagnostic logging |
 *
 * All public methods are called from the [SpeechToText] caller thread,
 * serialized via [SpeechToText.stateLock]. Internal state is guarded by
 * [lock] (synchronized block) for all writes. Read-only methods
 * ([captureMs], [hasPcmTimingStarted], [currentPcmElapsedMs]) also
 * acquire the lock for snapshot consistency. Worker threads reading
 * timing fields via these methods are safe because they never write.
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

    private val lock = Any()

    /** Session start wall time (ms), set by [beginSession]. 0 when no session active. */
    private var sessionStartMs: Long = 0L

    /** Inference start wall time (ms), set by [beginInference]. 0 when no inference active. */
    private var inferenceStartMs: Long = 0L

    /** Inference end wall time (ms), set by [endInference]. 0 when no inference has completed. */
    private var inferenceEndMs: Long = 0L

    // ── PCM timing fields (resettable per utterance) ──────────────────────

    /** PCM capture start wall time (ms), set when the processor starts. */
    private var timingPcmStartMs: Long = 0L

    /** Total PCM capture duration (ms) accumulated since last reset. */
    private var timingPcmTotalMs: Long = 0L

    /** Utterance start wall time (ms) — tracks pipeline start for timing snapshots. */
    private var timingUtteranceStartMs: Long = 0L

    /**
     * Begin a new session: record session start timestamp.
     * Resets utterance-level timing fields.
     */
    fun beginSession() {
        synchronized(lock) {
            sessionStartMs = System.currentTimeMillis()
            resetUtteranceTimingLocked()
        }
        SttLogger.lifecycle("SttSessionController: beginSession()")
    }

    /**
     * End the current session. Returns elapsed wall time in ms since [beginSession].
     * sessionStartMs is NOT reset — [resetSession] must be called for that.
     *
     * @return Elapsed ms since session start, or 0 if session was never started.
     */
    fun endSession(): Long {
        synchronized(lock) {
            if (sessionStartMs > 0) {
                return System.currentTimeMillis() - sessionStartMs
            }
            return 0L
        }
    }

    /**
     * Reset session state for a new utterance.
     * sessionStartMs is cleared.
     */
    fun resetSession() {
        synchronized(lock) {
            sessionStartMs = 0L
            resetUtteranceTimingLocked()
        }
        SttLogger.lifecycle("SttSessionController: resetSession()")
    }

    /**
     * Begin inference: record inference start timestamp.
     */
    fun beginInference() {
        synchronized(lock) {
            inferenceStartMs = System.currentTimeMillis()
        }
    }

    /**
     * End inference: record inference end timestamp and compute duration.
     *
     * @return Inference duration in ms, or 0 if inference was never started.
     */
    fun endInference(): Long {
        synchronized(lock) {
            inferenceEndMs = System.currentTimeMillis()
            if (inferenceStartMs > 0) {
                return inferenceEndMs - inferenceStartMs
            }
            return 0L
        }
    }

    /**
     * Begin PCM capture timing. Records the wall time when the processor starts.
     */
    fun beginPcmTiming() {
        synchronized(lock) {
            timingPcmStartMs = System.currentTimeMillis()
        }
        SttLogger.pcm("[TIMING] PCM capture start")
    }

    /**
     * End PCM capture timing. Computes total PCM capture duration.
     *
     * @return Total PCM capture duration in ms, or 0 if PCM timing was never started.
     */
    fun endPcmTiming(): Long {
        synchronized(lock) {
            if (timingPcmStartMs > 0) {
                timingPcmTotalMs = System.currentTimeMillis() - timingPcmStartMs
                return timingPcmTotalMs
            }
            return 0L
        }
    }

    /**
     * Record the utterance start wall time.
     * Called when the processor loop begins.
     */
    fun beginUtteranceTiming() {
        synchronized(lock) {
            timingUtteranceStartMs = System.currentTimeMillis()
        }
    }

    /**
     * Get elapsed ms since utterance start, used for timing snapshots.
     *
     * @return Elapsed ms since utterance start, or 0 if never started.
     */
    fun utteranceElapsedMs(): Long {
        synchronized(lock) {
            if (timingUtteranceStartMs > 0) {
                return System.currentTimeMillis() - timingUtteranceStartMs
            }
            return 0L
        }
    }

    /**
     * Return the utterance start wall clock timestamp (ms).
     * Used as a baseline for computing inference and total pipeline duration
     * inside [SttInferenceController].
     *
     * @return Wall clock timestamp (ms) of utterance start, or 0 if never started.
     */
    fun utteranceStartMs(): Long {
        return synchronized(lock) {
            timingUtteranceStartMs
        }
    }

    /**
     * Get the PCM capture duration in ms captured during [endPcmTiming].
     */
    fun captureMs(): Long {
        return synchronized(lock) {
            timingPcmTotalMs
        }
    }

    /**
     * Reset all utterance-level timing fields.
     */
    fun resetUtteranceTiming() {
        synchronized(lock) {
            resetUtteranceTimingLocked()
        }
    }

    /**
     * Returns true if PCM timing has started.
     */
    fun hasPcmTimingStarted(): Boolean {
        return synchronized(lock) {
            timingPcmStartMs > 0
        }
    }

    /**
     * Returns elapsed PCM wall time since beginPcmTiming without mutating stored totals.
     */
    fun currentPcmElapsedMs(): Long {
        return synchronized(lock) {
            if (timingPcmStartMs > 0) {
                System.currentTimeMillis() - timingPcmStartMs
            } else {
                0L
            }
        }
    }

    private fun resetUtteranceTimingLocked() {
        timingPcmStartMs = 0L
        timingPcmTotalMs = 0L
        timingUtteranceStartMs = 0L
        inferenceStartMs = 0L
        inferenceEndMs = 0L
    }
}
