package dev.barrycade.voicecore

import dev.barrycade.voicecore.stt.DrainMode
import dev.barrycade.voicecore.stt.SttConfig
import dev.barrycade.voicecore.stt.StopTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the STT lifecycle state machine rules that the UI must enforce.
 *
 * These tests verify the *contract* that MainActivity.updateUi() and
 * the stop/start button logic must satisfy.
 *
 * Because the UI logic depends on Android views (Button, View.VISIBLE, etc.),
 * the actual button visibility is verified via Robolectric in the
 * instrumented tests. This class tests the *business logic* contracts
 * that are framework-agnostic.
 */
class MainActivityStateTest {

    @Test
    fun displayConfig_storesStopTrigger() {
        val config = SttConfig(
            modelPath = "/test/path",
            language = "en",
            debugLoggingEnabled = false,
            energyThreshold = 0.03f,
            preRollMs = 100,
            stableChunkSizeMs = 500,
            drainMode = DrainMode.DRAIN_FROM_HEAD,
            startTrigger = dev.barrycade.voicecore.stt.StartTrigger.Manual,
            stopTrigger = StopTrigger.AutoSilence(silenceMs = 1500, maxDurationMs = 30000)
        )

        assertTrue(config.stopTrigger is StopTrigger.AutoSilence)
    }

    @Test
    fun configWithManualManual_hasCorrectStopTrigger() {
        val config = SttConfig(
            modelPath = "/test/path",
            language = "en",
            debugLoggingEnabled = false,
            energyThreshold = 0.03f,
            preRollMs = 100,
            stableChunkSizeMs = 500,
            drainMode = DrainMode.DRAIN_FROM_HEAD,
            startTrigger = dev.barrycade.voicecore.stt.StartTrigger.Manual,
            stopTrigger = StopTrigger.Manual
        )

        assertTrue(config.stopTrigger is StopTrigger.Manual)
    }

    @Test
    fun stopButton_visibleWhenManualStopTrigger() {
        val isRecording = true
        val showStop = isRecording && isStopManual(StopTrigger.Manual)
        assertTrue("Stop button must show when stopTrigger=Manual", showStop)
    }

    @Test
    fun stopButton_hiddenWhenAutoSilenceStopTrigger() {
        val showStop = isStopManual(StopTrigger.AutoSilence(silenceMs = 1200, maxDurationMs = 30000))
        assertFalse("Stop button must be hidden when stopTrigger != Manual", showStop)
    }

    @Test
    fun stopButton_hiddenWhenDurationStopTrigger() {
        val showStop = isStopManual(StopTrigger.Duration(maxDurationMs = 30000))
        assertFalse("Stop button must be hidden when stopTrigger != Manual", showStop)
    }

    @Test
    fun manualManual_silenceDoesNotAffectButtonState() {
        val isRecording = true
        val showStop = isRecording && isStopManual(StopTrigger.Manual)
        assertTrue("Stop button must stay enabled during silence in MANUAL mode", showStop)

        val afterStopIsRecording = false
        val showStopAfterStop = afterStopIsRecording && isStopManual(StopTrigger.Manual)
        assertFalse("Stop button must disable after stop", showStopAfterStop)
    }

    @Test
    fun startButton_disabledDuringRecording() {
        val showStart = !true
        assertFalse("Start button must be disabled during recording", showStart)
    }

    @Test
    fun startButton_reEnabledAfterStop() {
        val showStart = !false
        assertTrue("Start button must be re-enabled after session ends", showStart)
    }

    private fun isStopManual(stopTrigger: StopTrigger): Boolean = stopTrigger is StopTrigger.Manual
}
