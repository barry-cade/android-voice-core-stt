package dev.barrycade.voicecore.stt

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-owner holder for the stop-requested signal.
 *
 * Written in exactly one place ([raise]) and read via [isRequested].
 * This replaces the previous `@Volatile var stopRequested` pattern
 * which had three separate write sites in [SpeechToText].
 *
 * ## Ownership
 *
 * - Owner: [SpeechToText]
 * - Write site: [raise] — called from [SpeechToText.transcribe]
 * - Reset: [clear] — called on session reset and teardown
 * - Read: [isRequested] — polled by [MinimalPollingController] and [ProcessorController]
 *   via [asSupplier] lambda
 *
 * Thread-safe via [AtomicBoolean].
 */
internal class StopRequest {

    private val requested = AtomicBoolean(false)

    /**
     * Raise the stop signal. Called when the user requests stop.
     */
    fun raise() {
        requested.set(true)
    }

    /**
     * Clear the stop signal. Called when resetting for a new session.
     */
    fun clear() {
        requested.set(false)
    }

    /**
     * Returns true if stop has been requested.
     */
    fun isRequested(): Boolean = requested.get()

    /**
     * Returns a supplier lambda for injection into polling controllers.
     * The lambda reads the current flag value.
     */
    fun asSupplier(): () -> Boolean = { requested.get() }
}
