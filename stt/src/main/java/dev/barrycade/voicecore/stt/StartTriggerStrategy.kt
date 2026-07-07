package dev.barrycade.voicecore.stt

/**
 * Policy interface for deciding when recording should start.
 *
 * Implementations determine the trigger condition for beginning
 * PCM capture and VAD processing. The combination of [StartTriggerStrategy]
 * and [StopTriggerStrategy] defines the recording mode.
 *
 * @see ManualStartTrigger Explicit caller-requested start.
 */
internal interface StartTriggerStrategy {
    /**
     * Returns true when the engine should begin recording.
     *
     * Implementations should be idempotent: once [shouldStart] returns true,
     * subsequent calls should return false until [requestStart] (or equivalent)
     * is called again.
     */
    fun shouldStart(): Boolean
}
