package dev.barrycade.voicecore.stt

/**
 * Manual implementation of [StartTriggerStrategy].
 *
 * Recording begins only when the caller explicitly invokes [requestStart].
 * Once [shouldStart] returns true, the request is consumed and will not
 * trigger again until [requestStart] is called again.
 *
 * Preserves the current behaviour of explicit [SpeechToText.start] calls.
 */
internal class ManualStartTrigger : StartTriggerStrategy {
    @Volatile
    private var requested = false

    /**
     * Signal that a start has been requested.
     * The next call to [shouldStart] will return true.
     */
    fun requestStart() {
        requested = true
    }

    /**
     * Returns true if a start has been requested since the last check.
     * Consumes the request — subsequent calls return false
     * until [requestStart] is called again.
     */
    override fun shouldStart(): Boolean {
        if (requested) {
            requested = false
            return true
        }
        return false
    }
}
