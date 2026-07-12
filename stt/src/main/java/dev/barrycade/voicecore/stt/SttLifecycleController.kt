package dev.barrycade.voicecore.stt

/**
 * Owns the lifecycle state machine and enforces legal transitions.
 *
 * Responsibilities:
 * - Initialise the state machine via [onInit].
 * - Transition to READY via [onReady] when warm-up is complete.
 * - Transition to RECORDING via [onStart].
 * - Transition to STOPPED via [onStop].
 * - Reset to READY via [onReset].
 * - Tear down via [onDestroy].
 * - Handle warm-up replay logic (idempotent init).
 * - Expose [currentState] for querying by other controllers.
 *
 * No PCM, no threading, no mode branching — only lifecycle state.
 */
internal class SttLifecycleController {

    private val stateMachine = SttLifecycleStateMachine()

    /** Current lifecycle state, delegated to the state machine. */
    val currentState: SttLifecycleState
        get() = stateMachine.currentState

    /**
     * Initialise lifecycle: move from UNINITIALISED to INITIALISED.
     *
     * Safe to call multiple times — subsequent calls are no-ops when
     * already in INITIALISED or a later state.
     */
    fun onInit() {
        if (currentState !is SttLifecycleState.UNINITIALISED) return
        stateMachine.forceSet(SttLifecycleState.INITIALISED)
        SttLogger.lifecycle("SttLifecycleController: onInit() — state=INITIALISED")
    }

    /**
     * Transition to READY state after warm-up is complete.
     * Legal from: INITIALISED only.
     *
     * @return true if the transition was applied.
     */
    fun onReady(): Boolean {
        val result = stateMachine.transitionTo(SttLifecycleState.READY)
        if (result) {
            SttLogger.lifecycle("SttLifecycleController: onReady() — state=READY")
        }
        return result
    }

    /**
     * Transition to RECORDING state when capture starts.
     * Legal from: READY or INITIALISED.
     *
     * @return true if the transition was applied.
     */
    fun onStart(): Boolean {
        val result = stateMachine.transitionTo(SttLifecycleState.RECORDING)
        if (result) {
            SttLogger.lifecycle("SttLifecycleController: onStart() — state=RECORDING")
        }
        return result
    }

    /**
     * Transition to FINALISING state when stop is requested but inference
     * is still pending.
     *
     * Legal from: RECORDING, READY, or INITIALISED.
     * Uses forceSet to allow bypass from non-RECORDING states.
     *
     * @return true if the state was set to FINALISING.
     */
    fun onFinalising(): Boolean {
        val current = stateMachine.currentState
        if (current is SttLifecycleState.RECORDING) {
            return stateMachine.transitionTo(SttLifecycleState.FINALISING)
        }
        if (current is SttLifecycleState.READY || current is SttLifecycleState.INITIALISED) {
            stateMachine.forceSet(SttLifecycleState.FINALISING)
            SttLogger.lifecycle("SttLifecycleController: onFinalising() — forceSet to FINALISING")
            return true
        }
        return false
    }

    /**
     * Transition to STOPPED state when inference has been submitted or
     * no PCM was accumulated.
     *
     * Legal from: FINALISING only (or no-op if already STOPPED).
     */
    fun onStop() {
        if (stateMachine.currentState is SttLifecycleState.STOPPED) return
        stateMachine.transitionTo(SttLifecycleState.STOPPED)
        SttLogger.lifecycle("SttLifecycleController: onStop() — state=STOPPED")
    }

    /**
     * Reset to READY state for a new session.
     *
     * Uses forceSet to bypass from RECORDING, FINALISING, or STOPPED.
     * Safe to call multiple times; idempotent when already in READY or
     * INITIALISED.
     */
    fun onReset() {
        val current = stateMachine.currentState
        if (current is SttLifecycleState.RECORDING ||
            current is SttLifecycleState.FINALISING ||
            current is SttLifecycleState.STOPPED
        ) {
            stateMachine.forceSet(SttLifecycleState.READY)
            SttLogger.lifecycle("SttLifecycleController: onReset() — state=READY")
        }
    }

    /**
     * Tear down lifecycle: transition to UNINITIALISED.
     *
     * Uses forceSet from any state.
     */
    fun onDestroy() {
        val current = stateMachine.currentState
        if (current is SttLifecycleState.RECORDING ||
            current is SttLifecycleState.FINALISING
        ) {
            stateMachine.transitionTo(SttLifecycleState.STOPPED)
        }
        stateMachine.forceSet(SttLifecycleState.UNINITIALISED)
        SttLogger.lifecycle("SttLifecycleController: onDestroy() — state=UNINITIALISED")
    }

    /**
     * Returns true when the current state permits starting a session
     * (READY or INITIALISED).
     */
    fun canStartSession(): Boolean {
        return currentState is SttLifecycleState.READY ||
                currentState is SttLifecycleState.INITIALISED
    }

    /**
     * Returns true when the current state is RECORDING.
     */
    fun isRecording(): Boolean {
        return currentState is SttLifecycleState.RECORDING
    }
}
