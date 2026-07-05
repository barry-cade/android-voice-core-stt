package dev.barrycade.voicecore.stt

/**
 * Simple state holder for the STT pipeline lifecycle.
 * Transition validation and enforcement is performed by
 * [SpeechToText.transitionTo].
 *
 * Thread safety is delegated to the caller (SpeechToText uses [stateLock]).
 */
internal class SttLifecycleManager {
    @Volatile
    var currentState: SttLifecycleState = SttLifecycleState.UNINITIALISED
        internal set
}
