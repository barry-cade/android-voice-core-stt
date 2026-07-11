package dev.barrycade.voicecore.stt

/**
 * Decides when audio capture (session) should end.
 *
 * Strategies are fully orthogonal to [StartStrategy] — they do not
 * touch PCM, the accumulator, or inference.
 *
 * @see ManualStop Stops on explicit caller request.
 * @see AutoSilenceStop Stops after sustained silence or max duration.
 * @see DurationStop Stops after a fixed maximum duration.
 */
internal interface StopStrategy {
    /**
     * Returns true when the capture session should be stopped.
     *
     * Called repeatedly by [SpeechToText] while a session is active.
     * Once true, the caller finalises PCM and runs inference.
     *
     * @param events Observable events from the STT pipeline.
     * @param vad Current VAD state, or null if VAD is not yet initialised.
     * @param elapsedMs Milliseconds since the session began.
     */
    fun shouldStop(events: SttEvents, vad: Vad?, elapsedMs: Int): Boolean
}
