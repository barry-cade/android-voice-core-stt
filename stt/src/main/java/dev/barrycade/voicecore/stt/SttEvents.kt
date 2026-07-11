package dev.barrycade.voicecore.stt

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Observable events from the STT pipeline that strategies can react to.
 *
 * Each event is a toggle — set to true by the pipeline, consumed (reset to
 * false) by the strategy. Thread-safe via [AtomicBoolean].
 *
 * Strategies should call [consume] to read and reset an event atomically.
 *
 * @property manualStartPressed True when the caller requested a manual start.
 * @property manualStopPressed  True when the caller requested a manual stop.
 * @property wakeWordDetected   True when the wake-word detector fired.
 */
internal class SttEvents(
    val manualStartPressed: EventFlag = EventFlag(),
    val manualStopPressed: EventFlag = EventFlag(),
    val wakeWordDetected: EventFlag = EventFlag()
) {
    /**
     * A thread-safe boolean flag that is set once and consumed once.
     */
    class EventFlag {
        private val flag = AtomicBoolean(false)

        /** Set the flag to true. Idempotent — subsequent calls are no-ops until consumed. */
        fun raise() {
            flag.set(true)
        }

        /**
         * Atomically read and reset the flag.
         * @return true if the flag was raised (and this is the first consumption).
         */
        fun consume(): Boolean = flag.getAndSet(false)
    }
}
