package dev.barrycade.voicecore.stt

/**
 * STT pipeline lifecycle states.
 *
 * Legal transitions:
 *   UNINITIALISED → READY
 *   READY         → RECORDING
 *   RECORDING     → FINALISING
 *   FINALISING    → READY
 *
 * No other transitions are permitted. Any illegal transition produces
 * a [SttError] with code [SttErrorCode.PIPELINE_ILLEGAL_STATE].
 */
sealed class SttLifecycleState {

    /** Initial blank state; no resources allocated. */
    data object UNINITIALISED : SttLifecycleState()

    /** Configuration validated, ready to begin capture. */
    data object READY : SttLifecycleState()

    /** Microphone actively capturing PCM frames. */
    data object RECORDING : SttLifecycleState()

    /** Finalising capture and running Whisper inference. */
    data object FINALISING : SttLifecycleState()
}
