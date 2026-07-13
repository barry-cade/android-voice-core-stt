package dev.barrycade.voicecore.stt

/**
 * STT pipeline lifecycle states.
 *
 * Legal transitions:
 *   UNINITIALISED → INITIALISED
 *   INITIALISED   → READY
 *   READY         → RECORDING | STOPPED
 *   RECORDING     → FINALISING
 *   FINALISING    → STOPPED
 *   STOPPED       → READY (via onReset)
 *
 * [INITIALISED] represents the pre-wired state: CaptureManager is started but
 * no STT session is active. This enables invariant #1 (capture within 0-10ms
 * of Start) because the microphone is already running.
 *
 * [STOPPED] is the post-session state. Transition back to READY via
 * [SttLifecycleController.onReset] for the next utterance.
 *
 * No other transitions are permitted. Any illegal transition produces
 * a [SttError] with code [SttErrorCode.PIPELINE_ILLEGAL_STATE].
 */
internal sealed class SttLifecycleState {

    /** Initial blank state; no resources allocated. */
    data object UNINITIALISED : SttLifecycleState()

    /** CaptureManager pre-wired, microphone running. No session active. */
    data object INITIALISED : SttLifecycleState()

    /** Configuration validated, ready to begin capture. */
    data object READY : SttLifecycleState()

    /** Microphone actively capturing PCM frames. */
    data object RECORDING : SttLifecycleState()

    /** Finalising capture and running Whisper inference. */
    data object FINALISING : SttLifecycleState()

    /** Session complete; transitions back to READY via onReset. */
    data object STOPPED : SttLifecycleState()
}

