package dev.barrycade.voicecore.stt

/**
 * Manages the deterministic lifecycle state machine for the STT subsystem.
 *
 * Thread safety is delegated to the caller (SpeechToText uses [stateLock]).
 * All transition rules are enforced inside [transitionTo].
 */
internal class SttLifecycleManager(
    private val errorListener: SttErrorListener?
) {
    @Volatile
    var currentState: SttLifecycleState = SttLifecycleState.UNINITIALISED
        private set

    /**
     * Attempt to transition from [currentState] to [target].
     * Returns `true` if the transition was legal; `false` if it was a no-op
     * (already in the target state).
     *
     * Illegal transitions emit:
     *   1. A [SttError] with code [SttErrorCode.LIFECYCLE_VIOLATION]
     *   2. A "[LIFECYCLE] ERROR" log
     *   3. A callback to [SttErrorListener.onSttError]
     *
     * @throws IllegalStateException when the transition is invalid.
     */
    fun transitionTo(target: SttLifecycleState) {
        val from = currentState

        // No-op: already there.
        if (from == target) return

        if (isTransitionValid(from, target)) {
            SttLogger.lifecycle("state: ${from.javaClass.simpleName} → ${target.javaClass.simpleName}")
            currentState = target
        } else {
            val message = "Illegal lifecycle transition: ${from.javaClass.simpleName} → ${target.javaClass.simpleName}"
            SttLogger.lifecycleE(message)
            val error = SttError(
                code = SttErrorCode.LIFECYCLE_VIOLATION,
                message = message,
                context = mapOf(
                    "from" to from.javaClass.simpleName,
                    "to" to target.javaClass.simpleName
                )
            )
            errorListener?.onSttError(error)
            throw IllegalStateException(message)
        }
    }

    /**
     * Transition to DESTROYED regardless of current state.
     * This is the only unconditional transition (forced cleanup path).
     */
    fun transitionToDestroyed() {
        val from = currentState
        if (from is SttLifecycleState.DESTROYED) return
        SttLogger.lifecycle("state: ${from.javaClass.simpleName} → DESTROYED (forced)")
        currentState = SttLifecycleState.DESTROYED
    }

    /**
     * Returns true if the transition is allowed by the state machine.
     */
    private fun isTransitionValid(from: SttLifecycleState, to: SttLifecycleState): Boolean {
        return when (from) {
            is SttLifecycleState.UNINITIALISED -> to is SttLifecycleState.READY || to is SttLifecycleState.DESTROYED
            is SttLifecycleState.READY -> to is SttLifecycleState.RECORDING || to is SttLifecycleState.DESTROYED
            is SttLifecycleState.RECORDING -> to is SttLifecycleState.INFERENCING || to is SttLifecycleState.READY || to is SttLifecycleState.DESTROYED
            is SttLifecycleState.INFERENCING -> to is SttLifecycleState.RECORDING || to is SttLifecycleState.DESTROYED
            is SttLifecycleState.DESTROYED -> false // terminal — no transitions out
        }
    }
}
