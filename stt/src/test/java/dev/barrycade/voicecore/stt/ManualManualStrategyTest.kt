package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests asserting that MANUAL/MANUAL strategy is truly snap-on/snap-off.
 *
 * Invariants:
 * - VAD silence must NOT stop capture
 * - Long duration must NOT stop capture
 * - [ManualStop] only returns true when [manualStopPressed] is raised
 * - [ManualStart] only returns true when [manualStartPressed] is raised
 */
class ManualManualStrategyTest {

    @Test
    fun manualStart_onlyReturnsTrueOnPressedEvent() {
        val start = ManualStart()
        val events = SttEvents()

        // Without the event, shouldStart returns false.
        assertFalse("ManualStart without event must return false",
            start.shouldStart(events, null))

        // Raise the event.
        events.manualStartPressed.raise()
        assertTrue("ManualStart with event must return true",
            start.shouldStart(events, null))

        // Event consumed — second call returns false.
        assertFalse("ManualStart after consumption must return false",
            start.shouldStart(events, null))
    }

    @Test
    fun manualStop_onlyReturnsTrueOnPressedEvent() {
        val stop = ManualStop()
        val events = SttEvents()

        // Without the event, shouldStop returns false.
        assertFalse("ManualStop without event must return false",
            stop.shouldStop(events, null, 0))

        // Raise the event.
        events.manualStopPressed.raise()
        assertTrue("ManualStop with event must return true",
            stop.shouldStop(events, null, 0))

        // Event consumed — second call returns false.
        assertFalse("ManualStop after consumption must return false",
            stop.shouldStop(events, null, 0))
    }

    @Test
    fun manualStop_ignoresVadSilence() {
        val stop = ManualStop()
        val events = SttEvents()

        // A VAD with high silence duration must not trigger ManualStop.
        val vad = Vad(energyThreshold = 0.01)
        val silenceFrame = FloatArray(160) { 0.0f }
        // Feed many silence frames to build up silenceMs.
        repeat(100) { vad.isSpeech(silenceFrame) }

        // Without the event, shouldStop returns false even with lots of VAD silence.
        val shouldStop = stop.shouldStop(events, vad, 0)
        assertFalse("ManualStop must ignore VAD silence", shouldStop)
    }

    @Test
    fun manualStop_ignoresElapsedDuration() {
        val stop = ManualStop()
        val events = SttEvents()

        // Long elapsed duration must not trigger ManualStop.
        val longElapsedMs = 60000
        val shouldStop = stop.shouldStop(events, null, longElapsedMs)
        assertFalse("ManualStop must ignore elapsed duration", shouldStop)
    }

    @Test
    fun manualStop_onlyRespondsToEventNotVadOrDuration() {
        val stop = ManualStop()
        val events = SttEvents()

        // Feed silence into VAD and pass a large elapsed time.
        val vad = Vad(energyThreshold = 0.01)
        repeat(200) { vad.isSpeech(FloatArray(160) { 0.0f }) }

        // No event — should not stop regardless of VAD/duration.
        assertFalse(stop.shouldStop(events, vad, 99999))

        // Raise the event — should stop regardless of VAD/duration.
        events.manualStopPressed.raise()
        assertTrue("ManualStop must respond to event even with VAD silence",
            stop.shouldStop(events, vad, 99999))
    }

    @Test
    fun manualStart_ignoresVad() {
        val start = ManualStart()
        val events = SttEvents()

        // A VAD with ongoing speech must not trigger ManualStart.
        val vad = Vad(energyThreshold = 0.01)
        val speechFrame = FloatArray(160) { 0.3f }
        repeat(50) { vad.isSpeech(speechFrame) }

        // Without the event, shouldStart returns false even with VAD speech.
        assertFalse("ManualStart must ignore VAD", start.shouldStart(events, vad))

        // Raise the event — should start.
        events.manualStartPressed.raise()
        assertTrue("ManualStart must respond to event",
            start.shouldStart(events, vad))
    }
}
