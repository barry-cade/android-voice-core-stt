package dev.barrycade.voicecore.stt

/**
 * Policy interface for deciding when recording should stop and transcribe.
 *
 * Implementations determine the trigger condition for ending PCM capture,
 * finalising the utterance, and running Whisper inference.
 * The combination of [StartTriggerStrategy] and [StopTriggerStrategy]
 * defines the recording mode.
 *
 * @see ManualStopTrigger Explicit caller-requested stop.
 */
internal interface StopTriggerStrategy {
    /**
     * Returns true when the engine should stop recording and transcribe.
     *
     * Implementations should be idempotent: once [shouldStop] returns true,
     * subsequent calls should return false until [requestStop] (or equivalent)
     * is called again.
     */
    fun shouldStop(): Boolean
}
