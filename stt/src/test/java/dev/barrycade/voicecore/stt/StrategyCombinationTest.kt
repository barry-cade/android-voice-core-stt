package dev.barrycade.voicecore.stt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural tests for all strategy combinations.
 *
 * Tests focus on capture start/stop decisions only — not PCM or inference.
 * Each combination asserts that [shouldStart] and [shouldStop] produce the
 * correct decisions at the correct times.
 *
 * ## Coverage
 *
 * | Start | Stop | Test focus |
 * |---|---|---|
 * | MANUAL | MANUAL | Start only on event, stop only on event |
 * | VAD_START | MANUAL | Start on VAD threshold, stop on event |
 * | MANUAL | AUTO_SILENCE | Start on event, stop on silence or max duration |
 * | MANUAL | DURATION | Start on event, stop on exact duration |
 */
class StrategyCombinationTest {

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun createVad(threshold: Double = 0.01): Vad {
        return Vad(energyThreshold = threshold, frameDurationMs = 10)
    }

    private fun createSpeechFrame(): FloatArray = FloatArray(160) { 0.3f }

    private fun createSilenceFrame(): FloatArray = FloatArray(160) { 0.0f }

    /**
     * Feed speech frames into VAD until speech duration exceeds [minSpeechMs].
     */
    private fun feedSpeechUntil(vad: Vad, minSpeechMs: Int) {
        val speechFrame = createSpeechFrame()
        while (vad.speechDurationMs < minSpeechMs) {
            vad.isSpeech(speechFrame)
        }
    }

    /**
     * Feed silence frames into VAD until silence duration exceeds [silenceMs].
     */
    private fun feedSilenceUntil(vad: Vad, silenceMs: Int) {
        val silenceFrame = createSilenceFrame()
        while (vad.silenceMs < silenceMs) {
            vad.isSpeech(silenceFrame)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 1. MANUAL / MANUAL
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun manualManual_startOnlyOnEvent() {
        val start = ManualStart()
        val stop = ManualStop()
        val events = SttEvents()

        // No event — no start.
        assertFalse(start.shouldStart(events, null))

        // Raise event — start.
        events.manualStartPressed.raise()
        assertTrue(start.shouldStart(events, null))

        // Event consumed — no more starts.
        assertFalse(start.shouldStart(events, null))

        // Stop: without event, no stop.
        assertFalse(stop.shouldStop(events, null, 0))

        // Raise stop event.
        events.manualStopPressed.raise()
        assertTrue(stop.shouldStop(events, null, 0))

        // Event consumed.
        assertFalse(stop.shouldStop(events, null, 0))
    }

    // ══════════════════════════════════════════════════════════════════════
    // 2. VAD_START / MANUAL
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun vadStartManual_captureStartsOnVadThreshold() {
        val start = VadStart(VadStartConfig(vadStartThreshold = 0.05f, minSpeechMs = 100))
        val events = SttEvents()
        val vad = createVad()

        // Initially — VAD has no speech, should not start.
        assertFalse("VAD_START must not start before speech threshold",
            start.shouldStart(events, vad))

        // Feed speech until VAD threshold is met.
        feedSpeechUntil(vad, 100)

        // Now the VAD has sustained speech — should start.
        assertTrue("VAD_START must start when VAD threshold is met",
            start.shouldStart(events, vad))

        // Strategy is stateless — while VAD still reports speech, it still
        // returns true. The caller (SpeechToText) gates on state machine.
        assertTrue("VAD_START returns true while VAD conditions hold (stateless)",
            start.shouldStart(events, vad))
    }

    @Test
    fun vadStartManual_stopOnlyOnEvent() {
        val start = VadStart(VadStartConfig(vadStartThreshold = 0.05f, minSpeechMs = 100))
        val stop = ManualStop()
        val events = SttEvents()
        val vad = createVad()

        // Start via VAD.
        feedSpeechUntil(vad, 100)
        assertTrue(start.shouldStart(events, vad))

        // Stop: VAD silence must NOT trigger ManualStop.
        feedSilenceUntil(vad, 5000)
        assertFalse("MANUAL stop must not trigger on VAD silence",
            stop.shouldStop(events, vad, 0))

        // Stop: long duration must NOT trigger ManualStop.
        assertFalse("MANUAL stop must not trigger on duration",
            stop.shouldStop(events, vad, 60000))

        // Stop: only on manual stop event.
        events.manualStopPressed.raise()
        assertTrue("MANUAL stop must trigger on event",
            stop.shouldStop(events, vad, 0))
    }

    @Test
    fun vadStartManual_vadBelowThreshold_doesNotStart() {
        val start = VadStart(VadStartConfig(vadStartThreshold = 0.05f, minSpeechMs = 100))
        val events = SttEvents()
        val vad = createVad()

        // Insufficient speech — below minSpeechMs.
        val speechFrame = createSpeechFrame()
        repeat(5) { vad.isSpeech(speechFrame) }  // ~50ms, below 100ms

        assertFalse("VAD_START must not start below minSpeechMs",
            start.shouldStart(events, vad))
    }

    // ══════════════════════════════════════════════════════════════════════
    // 3. MANUAL / AUTO_SILENCE
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun manualAutoSilence_startOnlyOnEvent() {
        val start = ManualStart()
        val stop = AutoSilenceStop(AutoSilenceConfig(silenceMs = 500, maxDurationMs = 30000))
        val events = SttEvents()

        // No event — no start.
        assertFalse(start.shouldStart(events, null))

        // Raise event — start.
        events.manualStartPressed.raise()
        assertTrue(start.shouldStart(events, null))

        // Event consumed.
        assertFalse(start.shouldStart(events, null))
    }

    @Test
    fun manualAutoSilence_stopOnVadSilence() {
        val stop = AutoSilenceStop(AutoSilenceConfig(silenceMs = 500, maxDurationMs = 30000))
        val events = SttEvents()
        val vad = createVad()

        // Silence has not yet reached threshold — should not stop.
        repeat(20) { vad.isSpeech(createSpeechFrame()) }  // ~200ms speech
        repeat(20) { vad.isSpeech(createSilenceFrame()) } // ~200ms silence, below 500ms
        assertFalse(stop.shouldStop(events, vad, 0))

        // Feed more silence to exceed threshold.
        feedSilenceUntil(vad, 500)
        assertTrue("AUTO_SILENCE must stop on VAD silence threshold",
            stop.shouldStop(events, vad, 0))

        // Strategy is stateless — while VAD still reports silence >= threshold,
        // it still returns true. The caller (SpeechToText) gates on state machine.
        assertTrue("AUTO_SILENCE returns true while silence condition holds (stateless)",
            stop.shouldStop(events, vad, 0))
    }

    @Test
    fun manualAutoSilence_stopOnMaxDuration() {
        val stop = AutoSilenceStop(AutoSilenceConfig(silenceMs = 5000, maxDurationMs = 1000))
        val vad = createVad()

        // Feed speech (silence threshold will not be hit).
        feedSpeechUntil(vad, 200)
        repeat(10) { vad.isSpeech(createSilenceFrame()) }  // ~100ms silence, well below 5000ms

        // But elapsed time exceeds maxDurationMs.
        assertTrue("AUTO_SILENCE must stop on max duration",
            stop.shouldStop(SttEvents(), vad, 1500))

        // After stopping, subsequent call with same elapsedMs returns false
        // because the strategy doesn't track state across calls.
    }

    @Test
    fun manualAutoSilence_doesNotStopOnManualEvent() {
        val stop = AutoSilenceStop(AutoSilenceConfig(silenceMs = 500, maxDurationMs = 30000))
        val events = SttEvents()
        val vad = createVad()

        // Raise manual stop event.
        events.manualStopPressed.raise()

        // AutoSilenceStop ignores events — should not stop based on event alone.
        repeat(10) { vad.isSpeech(createSpeechFrame()) }
        assertFalse("AUTO_SILENCE must ignore manual stop events",
            stop.shouldStop(events, vad, 0))
    }

    // ══════════════════════════════════════════════════════════════════════
    // 4. MANUAL / DURATION
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun manualDuration_startOnlyOnEvent() {
        val start = ManualStart()
        val stop = DurationStop(maxDurationMs = 5000)
        val events = SttEvents()

        // No event — no start.
        assertFalse(start.shouldStart(events, null))

        // Raise event — start.
        events.manualStartPressed.raise()
        assertTrue(start.shouldStart(events, null))
    }

    @Test
    fun manualDuration_stopOnExactDuration() {
        val stop = DurationStop(5000)
        val vad = createVad()

        // Below max duration — should not stop.
        assertFalse("DURATION must not stop before maxDurationMs",
            stop.shouldStop(SttEvents(), vad, 1000))

        // At exactly max duration — should stop.
        assertTrue("DURATION must stop at maxDurationMs",
            stop.shouldStop(SttEvents(), vad, 5000))

        // After stopping — should still stop (duration is absolute, not stateful).
        assertTrue("DURATION must continue to stop after maxDurationMs",
            stop.shouldStop(SttEvents(), vad, 5001))
    }

    @Test
    fun manualDuration_ignoresVadAndEvents() {
        val stop = DurationStop(5000)
        val events = SttEvents()
        val vad = createVad()

        // Feed VAD speech and silence.
        feedSpeechUntil(vad, 500)
        feedSilenceUntil(vad, 500)

        // Raise manual stop event.
        events.manualStopPressed.raise()

        // DurationStop ignores both VAD and events — only elapsedMs matters.
        assertFalse("DURATION must ignore VAD and events below maxDurationMs",
            stop.shouldStop(events, vad, 3000))

        assertTrue("DURATION must stop at maxDurationMs regardless of VAD/events",
            stop.shouldStop(events, vad, 5000))
    }

}
