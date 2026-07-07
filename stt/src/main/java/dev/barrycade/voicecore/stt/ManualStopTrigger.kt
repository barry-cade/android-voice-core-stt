package dev.barrycade.voicecore.stt

/**
 * Manual implementation of [StopTriggerStrategy].
 *
 * Recording ends only when the caller explicitly invokes [requestStop].
 * Once [shouldStop] returns true, the request is consumed and will not
 * trigger again until [requestStop] is called again.
 *
 * Preserves the current behaviour of explicit [SpeechToText.stopAndTranscribe]
 * and [SpeechToText.stop] calls.
 */
internal class ManualStopTrigger : StopTriggerStrategy {
    @Volatile
    private var requested = false

    /**
     * Signal that a stop has been requested.
     * The next call to [shouldStop] will return true.
     */
    fun requestStop() {
        requested = true
    }

    /**
     * Returns true if a stop has been requested since the last check.
     * Consumes the request — subsequent calls return false
     * until [requestStop] is called again.
     */
    override fun shouldStop(): Boolean {
        if (requested) {
            requested = false
            return true
        }
        return false
    }
}
