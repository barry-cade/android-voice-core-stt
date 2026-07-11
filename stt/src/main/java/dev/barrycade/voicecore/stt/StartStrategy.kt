package dev.barrycade.voicecore.stt

/**
 * Decides when audio capture (session) should begin.
 *
 * Strategies are fully orthogonal to [StopStrategy] — they do not
 * touch PCM, the accumulator, or inference.
 *
 * @see ManualStart Begins on explicit caller request.
 * @see VadStart Begins when VAD detects sustained speech above a threshold.
 * @see WakeWordStart Begins when a wake word is detected (placeholder).
 */
internal interface StartStrategy {
    /**
     * Returns true when the capture session should be started.
     *
     * Called repeatedly by [SpeechToText] while no session is active.
     * Once true, the caller begins PCM accumulation via [SessionManager.begin].
     *
     * @param events Observable events from the STT pipeline.
     * @param vad Current VAD state, or null if VAD is not yet initialised.
     */
    fun shouldStart(events: SttEvents, vad: Vad?): Boolean
}
