package dev.barrycade.voicecore.stt

// TODO(major-version): Consolidate trigger system once legacy SttConfig is removed.

/**
 * Manual implementation of [StartTriggerStrategy].
 *
 * Recording begins only when the caller explicitly invokes [SpeechToText.start].
 * [shouldStart] always returns true because the engine checks it exactly once
 * per [SpeechToText.start] call — the explicit call *is* the request.
 *
 * Preserves the current behaviour of explicit caller-driven start.
 */
internal class ManualStartTrigger : StartTriggerStrategy {
    override fun shouldStart(): Boolean = true
}
