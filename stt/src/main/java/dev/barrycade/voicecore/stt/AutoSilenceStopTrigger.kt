package dev.barrycade.voicecore.stt

/**
 * Auto-silence implementation of [StopTriggerStrategy].
 *
 * Recording ends when silence exceeds [silenceThresholdMs] **after** speech
 * has been detected. Pre-speech silence is ignored — the trigger only
 * activates following a call to [onSpeechDetected].
 *
 * Internally flags are consumed once on the next [shouldStop] call, ensuring
 * idempotent shouldStop() behaviour.
 *
 * @param silenceThresholdMs Minimum consecutive silence duration (ms) that
 *        triggers a stop, measured from the last speech frame.
 */
internal class AutoSilenceStopTrigger(
    private val silenceThresholdMs: Long
) : StopTriggerStrategy {

    @Volatile
    private var shouldStopFlag = false

    @Volatile
    private var speechHasOccurred = false

    /**
     * Called when speech is detected — resets silence accumulation and
     * marks that speech has occurred. Only after this call will subsequent
     * [onSilenceDetected] calls be able to set the stop flag.
     */
    fun onSpeechDetected() {
        speechHasOccurred = true
        shouldStopFlag = false
    }

    /**
     * Called externally when silence duration is detected.
     * Only sets the stop flag when speech has previously occurred and the
     * silence duration meets or exceeds the threshold.
     */
    fun onSilenceDetected(durationMs: Long) {
        if (!speechHasOccurred) return
        if (durationMs >= silenceThresholdMs) {
            shouldStopFlag = true
        }
    }

    /**
     * Returns true once when the silence threshold has been exceeded.
     * The flag is consumed on the first call, after which subsequent calls
     * return false until [onSilenceDetected] sets it again.
     */
    override fun shouldStop(): Boolean {
        if (shouldStopFlag) {
            shouldStopFlag = false
            return true
        }
        return false
    }

    /** Reset all state — used when the trigger is re-used across sessions. */
    fun reset() {
        shouldStopFlag = false
        speechHasOccurred = false
    }
}
