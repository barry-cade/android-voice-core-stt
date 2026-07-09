package dev.barrycade.voicecore.stt

// TODO(major-version): Consolidate trigger system once legacy SttConfig is removed.

/**
 * Manual implementation of [StopTriggerStrategy].
 *
 * Recording ends only when the caller explicitly invokes [SpeechToText.stopAndTranscribe]
 * or [SpeechToText.stop]. [shouldStop] always returns true because the engine checks
 * it exactly once per [SpeechToText.stopAndTranscribe] call — the explicit call
 * *is* the request.
 *
 * Preserves the current behaviour of explicit caller-driven stop.
 */
internal class ManualStopTrigger : StopTriggerStrategy {
    override fun shouldStop(): Boolean = true
}
