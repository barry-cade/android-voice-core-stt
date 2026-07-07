package dev.barrycade.voicecore.stt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [AutoSilenceStopTrigger] flag behaviour.
 *
 * All tests that involve silence detection must first call [onSpeechDetected]
 * to bypass the pre-speech silence guard, unless they are explicitly testing
 * the guard itself.
 */
class AutoSilenceStopTriggerTest {

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun createTrigger(thresholdMs: Long = 500L): AutoSilenceStopTrigger {
        val trigger = AutoSilenceStopTrigger(silenceThresholdMs = thresholdMs)
        trigger.onSpeechDetected()
        return trigger
    }

    // ── Constructor ────────────────────────────────────────────────────────

    @Test
    fun constructor_acceptsThreshold() {
        val trigger = AutoSilenceStopTrigger(silenceThresholdMs = 500L)
        assertNotNull(trigger)
    }

    // ── Pre-speech silence guard ───────────────────────────────────────────

    @Test
    fun preSpeechSilence_doesNotTrigger_withoutSpeechDetected() {
        val trigger = AutoSilenceStopTrigger(silenceThresholdMs = 500L)
        trigger.onSilenceDetected(5000L)
        assertFalse("Pre-speech silence must not set stop flag",
            trigger.shouldStop())
    }

    @Test
    fun preSpeechSilence_doesNotTrigger_evenWithZeroThreshold() {
        val trigger = AutoSilenceStopTrigger(silenceThresholdMs = 0L)
        trigger.onSilenceDetected(100L)
        assertFalse("Pre-speech silence must not set stop flag even with zero threshold",
            trigger.shouldStop())
    }

    // ── onSilenceDetected (with speechHasOccurred) ─────────────────────────

    @Test
    fun onSilenceDetected_belowThreshold_doesNotTrigger() {
        val trigger = createTrigger()
        trigger.onSilenceDetected(300L)
        assertFalse("shouldStop must be false when silence < threshold",
            trigger.shouldStop())
    }

    @Test
    fun onSilenceDetected_atThreshold_triggersStop() {
        val trigger = createTrigger()
        trigger.onSilenceDetected(500L)
        assertTrue("shouldStop must be true when silence == threshold",
            trigger.shouldStop())
    }

    @Test
    fun onSilenceDetected_aboveThreshold_triggersStop() {
        val trigger = createTrigger()
        trigger.onSilenceDetected(700L)
        assertTrue("shouldStop must be true when silence > threshold",
            trigger.shouldStop())
    }

    @Test
    fun shouldStop_consumesFlagOnFirstCall() {
        val trigger = createTrigger()
        trigger.onSilenceDetected(500L)

        assertTrue("First shouldStop() must return true", trigger.shouldStop())
        assertFalse("Second shouldStop() must return false (flag consumed)",
            trigger.shouldStop())
    }

    @Test
    fun shouldStop_returnsFalseAfterConsumption() {
        val trigger = createTrigger()
        trigger.onSilenceDetected(500L)

        trigger.shouldStop() // consume
        assertFalse("shouldStop() after consumption must return false",
            trigger.shouldStop())
    }

    @Test
    fun multipleSilenceEvents_resetsFlag() {
        val trigger = createTrigger()

        // First detection and consumption
        trigger.onSilenceDetected(500L)
        assertTrue(trigger.shouldStop())
        assertFalse(trigger.shouldStop())

        // Second detection — flag resets
        trigger.onSilenceDetected(500L)
        assertTrue("Flag must be settable again after consumption",
            trigger.shouldStop())
    }

    @Test
    fun shouldStop_default_isFalse() {
        val trigger = AutoSilenceStopTrigger(silenceThresholdMs = 500L)
        assertFalse("shouldStop() must default to false", trigger.shouldStop())
    }

    @Test
    fun zeroThreshold_triggersOnAnySilence() {
        val trigger = createTrigger(thresholdMs = 0L)
        trigger.onSilenceDetected(1L)
        assertTrue("With threshold=0, any silence after speech must trigger",
            trigger.shouldStop())
    }

    // ── onSpeechDetected ───────────────────────────────────────────────────

    @Test
    fun onSpeechDetected_clearsPreSpeechGuard() {
        val trigger = AutoSilenceStopTrigger(silenceThresholdMs = 500L)

        // Silence before speech — must not trigger
        trigger.onSilenceDetected(500L)
        assertFalse(trigger.shouldStop())

        // Speech detected — guard cleared
        trigger.onSpeechDetected()

        // Silence after speech — must trigger
        trigger.onSilenceDetected(500L)
        assertTrue("Silence after speech must trigger", trigger.shouldStop())
    }

    @Test
    fun onSpeechDetected_clearsStopFlag() {
        val trigger = createTrigger()
        trigger.onSilenceDetected(500L)
        assertTrue(trigger.shouldStop())

        // Speech resets the stop flag
        trigger.onSpeechDetected()
        assertFalse("onSpeechDetected must clear the stop flag",
            trigger.shouldStop())
    }

    // ── reset ──────────────────────────────────────────────────────────────

    @Test
    fun reset_clearsAllState() {
        val trigger = createTrigger()
        trigger.onSilenceDetected(500L)
        assertTrue(trigger.shouldStop())

        trigger.reset()

        // After reset, pre-speech guard is re-activated
        trigger.onSilenceDetected(5000L)
        assertFalse("After reset, pre-speech silence must not trigger",
            trigger.shouldStop())

        // Speech re-enables detection
        trigger.onSpeechDetected()
        trigger.onSilenceDetected(500L)
        assertTrue("After speech post-reset, silence must trigger",
            trigger.shouldStop())
    }

    // ── Scenario-based integration tests ───────────────────────────────────

    @Test
    fun scenario_startSilenceNoSpeech_autoStopDoesNotFire() {
        val trigger = AutoSilenceStopTrigger(silenceThresholdMs = 500L)

        // No speech ever — silence accumulates
        trigger.onSilenceDetected(300L)
        assertFalse(trigger.shouldStop())
        trigger.onSilenceDetected(600L)
        assertFalse(trigger.shouldStop())
        trigger.onSilenceDetected(5000L)
        assertFalse("No speech occurred — must not auto-stop",
            trigger.shouldStop())
    }

    @Test
    fun scenario_speechThenSilence_autoStopFires() {
        val trigger = createTrigger()

        // Speech detected
        // (createTrigger already called onSpeechDetected)

        // Silence accumulates
        trigger.onSilenceDetected(300L)
        assertFalse(trigger.shouldStop())
        trigger.onSilenceDetected(500L)
        assertTrue("Silence >= threshold after speech must trigger",
            trigger.shouldStop())
    }

    @Test
    fun scenario_speechThenSpeechThenSilence_autoStopFires() {
        val trigger = createTrigger()

        // More speech resets silence accumulation
        trigger.onSpeechDetected()

        // Silence accumulates
        trigger.onSilenceDetected(300L)
        assertFalse(trigger.shouldStop())
        trigger.onSilenceDetected(500L)
        assertTrue(trigger.shouldStop())
    }

    @Test
    fun scenario_silenceThenSpeechThenSilence_autoStopFires() {
        val trigger = AutoSilenceStopTrigger(silenceThresholdMs = 500L)

        // Pre-speech silence — ignored
        trigger.onSilenceDetected(1000L)
        assertFalse(trigger.shouldStop())

        // Speech detected
        trigger.onSpeechDetected()

        // Post-speech silence
        trigger.onSilenceDetected(300L)
        assertFalse(trigger.shouldStop())
        trigger.onSilenceDetected(500L)
        assertTrue("Silence after speech must trigger even after pre-speech silence",
            trigger.shouldStop())
    }

    @Test
    fun scenario_silenceSilenceSilence_noAutoStop() {
        val trigger = AutoSilenceStopTrigger(silenceThresholdMs = 500L)

        // Three periods of silence, no speech
        trigger.onSilenceDetected(600L)
        assertFalse(trigger.shouldStop())
        trigger.onSilenceDetected(600L)
        assertFalse(trigger.shouldStop())
        trigger.onSilenceDetected(600L)
        assertFalse("No speech at any point — must not auto-stop",
            trigger.shouldStop())
    }
}
