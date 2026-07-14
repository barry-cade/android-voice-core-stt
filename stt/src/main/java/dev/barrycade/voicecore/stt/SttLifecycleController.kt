package dev.barrycade.voicecore.stt

/**
 * Owns the lifecycle state machine and enforces legal transitions.
 *
 * ## Transition intent mapping
 *
 * Each public method represents a single transition intent.
 * The method name describes what happened (e.g. [onReady], [onStart]),
 * not what state we're moving to.
 *
 * ## Legal transitions
 *
 *   UNINITIALISED → INITIALISED  (onInit)
 *   INITIALISED   → READY        (onReady)
 *   READY         → RECORDING    (onStart)
 *   RECORDING     → FINALISING   (onFinalising)
 *   FINALISING    → STOPPED      (onStop)
 *   STOPPED       → READY        (onReset / normal path)
 *
 * ## Bypass transitions (documented exceptions)
 *
 * These bypasses skip intermediate states. Each is documented with
 * the specific reason the bypass is necessary. Bypass transitions
 * use [SttLifecycleStateMachine.forceSet].
 *
 *   1. READY/INITIALISED → FINALISING  (onFinalising)
 *      Reason: stopAndTranscribe() may be called before onStart()
 *      has transitioned to RECORDING (early stop path).
 *
 *   2. RECORDING/FINALISING → READY    (onReset)
 *      Reason: resetForNextSession() or destroy() may be called
 *      while still recording/finalising (abnormal teardown).
 *
 *   3. Any → UNINITIALISED             (onDestroy)
 *      Reason: terminal teardown must complete from any state.
 *
 * No PCM, no threading, no mode branching — only lifecycle state.
 */
internal class SttLifecycleController {

    private val stateMachine = SttLifecycleStateMachine()

    /** Current lifecycle state, delegated to the state machine. */
    val currentState: SttLifecycleState
        get() = stateMachine.currentState

    /**
     * Initialise lifecycle: transition UNINITIALISED → INITIALISED.
     *
     * Legal from: UNINITIALISED only.
     * Safe to call multiple times — subsequent calls are no-ops when
     * already in INITIALISED or a later state.
     */
    fun onInit() {
        if (currentState !is SttLifecycleState.UNINITIALISED) return
        stateMachine.transitionTo(SttLifecycleState.INITIALISED)
        SttLogger.lifecycle("SttLifecycleController: onInit() — state=INITIALISED")
    }

    /**
     * Transition to READY state: INITIALISED → READY.
     *
     * Called after warm-up is complete.
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
     * Transition to RECORDING state: READY → RECORDING.
     *
     * Called when PCM capture begins.
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
     * Transition to FINALISING state: RECORDING → FINALISING.
     *
     * Called when stop is requested but inference is still pending.
     *
     * **Bypass:** If the session never reached RECORDING (stop was
     * called before onStart transitioned), then READY or INITIALISED
     * may be forceSet to FINALISING. This is intentional — the
     * stop path needs to finalise regardless of whether start
     * completed. See bypass case 1 in the class docs.
     *
     * @return true if the state was set to FINALISING.
     */
    fun onFinalising(): Boolean {
        val current = stateMachine.currentState
        if (current is SttLifecycleState.RECORDING) {
            return stateMachine.transitionTo(SttLifecycleState.FINALISING)
        }
        // Bypass case 1: stop before start completed.
        if (current is SttLifecycleState.READY || current is SttLifecycleState.INITIALISED) {
            stateMachine.forceSet(SttLifecycleState.FINALISING)
            SttLogger.lifecycle("SttLifecycleController: onFinalising() — forceSet to FINALISING (bypass from ${current::class.simpleName})")
            return true
        }
        return false
    }

    /**
     * Transition to STOPPED state: FINALISING → STOPPED.
     *
     * Called when inference has completed or was skipped.
     * After STOPPED, call [onReset] to return to READY for the
     * next utterance.
     */
    fun onStop() {
        if (stateMachine.currentState is SttLifecycleState.STOPPED) return
        stateMachine.transitionTo(SttLifecycleState.STOPPED)
        SttLogger.lifecycle("SttLifecycleController: onStop() — state=STOPPED")
    }

    /**
     * Reset to READY state for a new session.
     *
     * **Normal path:** STOPPED → READY via legal [transitionTo].
     *
     * **Bypass:** RECORDING or FINALISING → READY via [forceSet].
     * This occurs when [SpeechToText.resetForNextSession] or
     * [SpeechToText.destroy] is called while the pipeline is still
     * active. See bypass case 2 in the class docs.
     *
     * Safe to call multiple times. No-op when already in READY or
     * INITIALISED.
     */
    fun onReset() {
        val current = stateMachine.currentState
        when {
            current is SttLifecycleState.STOPPED -> {
                stateMachine.transitionTo(SttLifecycleState.READY)
                SttLogger.lifecycle("SttLifecycleController: onReset() — state=READY (from STOPPED)")
            }
            current is SttLifecycleState.RECORDING ||
                    current is SttLifecycleState.FINALISING -> {
                stateMachine.forceSet(SttLifecycleState.READY)
                SttLogger.lifecycle("SttLifecycleController: onReset() — state=READY (bypass from ${current::class.simpleName})")
            }
        }
    }

    /**
     * Tear down lifecycle: forceSet to UNINITIALISED from any state.
     *
     * This is always a bypass — destroy must terminate from any
     * lifecycle state. See bypass case 3 in the class docs.
     */
    fun onDestroy() {
        val previousState = stateMachine.currentState
        stateMachine.forceSet(SttLifecycleState.UNINITIALISED)
        val logMessage = if (previousState is SttLifecycleState.RECORDING ||
            previousState is SttLifecycleState.FINALISING
        ) {
            "SttLifecycleController: onDestroy() — state=UNINITIALISED (bypass from ${previousState::class.simpleName})"
        } else {
            "SttLifecycleController: onDestroy() — state=UNINITIALISED"
        }
        SttLogger.lifecycle(logMessage)
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
