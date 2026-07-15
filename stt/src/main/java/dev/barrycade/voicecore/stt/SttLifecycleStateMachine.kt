package dev.barrycade.voicecore.stt

/**
 * Thread-safe state machine for STT lifecycle transitions.
 *
 * Encapsulates its own [lock] so callers do not need to hold an external
 * lock when calling [transitionTo] or [forceSet]. Read access via [currentState]
 * is a volatile field and does not require the lock.
 *
 * Legal transitions:
 *   UNINITIALISED → INITIALISED
 *   INITIALISED   → READY
 *   READY         → RECORDING | STOPPED
 *   RECORDING     → FINALISING
 *   FINALISING    → STOPPED
 *   STOPPED       → READY
 *
 * All other transitions are illegal and return false.
 * Duplicate transitions (same state → same state) are allowed as no-ops.
 */
internal class SttLifecycleStateMachine(
    private val sttErrorListener: SttErrorListener? = null
) {

    private val lock = Any()

    @Volatile
    private var _currentState: SttLifecycleState = SttLifecycleState.UNINITIALISED

    /**
     * Read-only snapshot of the current state.
     * Thread-safe without lock — backed by [@Volatile].
     */
    val currentState: SttLifecycleState
        get() = _currentState

    /**
     * Transition to [newState] following the legal transition matrix.
     *
     * @return true if the transition was applied, false if illegal.
     *         Duplicate transitions return true (no-op).
     */
    fun transitionTo(newState: SttLifecycleState): Boolean {
        synchronized(lock) {
            val from = _currentState
            if (from == newState) return true

            val valid = when (from) {
                is SttLifecycleState.UNINITIALISED -> newState is SttLifecycleState.INITIALISED
                is SttLifecycleState.INITIALISED -> newState is SttLifecycleState.READY
                is SttLifecycleState.READY -> newState is SttLifecycleState.RECORDING ||
                        newState is SttLifecycleState.STOPPED
                is SttLifecycleState.RECORDING -> newState is SttLifecycleState.FINALISING
                is SttLifecycleState.FINALISING -> newState is SttLifecycleState.STOPPED
                is SttLifecycleState.STOPPED -> newState is SttLifecycleState.READY
            }

            if (valid) {
                _currentState = newState
                return true
            }

            val fromName = from.javaClass.simpleName
            val toName = newState.javaClass.simpleName
            SttLogger.lifecycleE("illegal transition: $fromName -> $toName")
            sttErrorListener?.onSttError(SttError(
                code = SttErrorCode.PIPELINE_ILLEGAL_STATE,
                message = "Illegal lifecycle transition: $fromName -> $toName",
                details = listOf("from=$fromName", "to=$toName")
            ))
            return false
        }
    }

    /**
     * Force-set the state without validation.
     *
     * Use only for bypass paths where the normal lifecycle has not been
     * followed (e.g. early stop during warm-up, direct assignment in
     * [destroy], or initial transition from UNINITIALISED to INITIALISED
     * which occurs when CaptureManager is pre-wired in the constructor).
     * Each call site MUST document why the bypass is necessary.
     *
     * Thread-safe: guarded by internal lock.
     */
    fun forceSet(state: SttLifecycleState) {
        synchronized(lock) {
            _currentState = state
        }
    }
}

