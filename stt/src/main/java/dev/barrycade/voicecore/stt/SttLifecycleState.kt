package dev.barrycade.voicecore.stt

/**
 * Sealed lifecycle state machine for the STT subsystem.
 *
 * Valid transitions:
 *   UNINITIALISED → READY           (after config validation / preparation)
 *   READY         → RECORDING       (when audio capture starts)
 *   RECORDING     → INFERENCING     (when Whisper inference is running)
 *   INFERENCING   → RECORDING       (back to recording after inference completes)
 *   RECORDING     → READY           (when user stops; clean standby)
 *   READY         → DESTROYED       (shutdown path)
 *   RECORDING     → DESTROYED       (direct teardown)
 *   INFERENCING   → DESTROYED       (direct teardown)
 *   Any           → DESTROYED       (forced cleanup on destroy)
 *
 * Any unlisted transition is illegal and must produce
 * a [SttError] with code [SttErrorCode.LIFECYCLE_VIOLATION].
 */
sealed class SttLifecycleState {
    /** No resources allocated; initial blank state. */
    data object UNINITIALISED : SttLifecycleState()

    /** Configuration validated, ready to begin capture. */
    data object READY : SttLifecycleState()

    /** Microphone actively capturing PCM frames. */
    data object RECORDING : SttLifecycleState()

    /** Whisper inference in progress. */
    data object INFERENCING : SttLifecycleState()

    /** All resources released; terminal state. */
    data object DESTROYED : SttLifecycleState()
}
