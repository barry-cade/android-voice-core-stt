package dev.barrycade.voicecore.stt

/**
 * [StartStrategy] that begins capture on explicit caller request.
 *
 * Delegates to [SttEvents.manualStartPressed] — the caller raises the
 * event via [SpeechToText.startSession], and this strategy consumes it.
 *
 * @see ManualStop The corresponding stop strategy.
 */
internal class ManualStart : StartStrategy {
    override fun shouldStart(events: SttEvents, vad: Vad?): Boolean {
        return events.manualStartPressed.consume()
    }
}
