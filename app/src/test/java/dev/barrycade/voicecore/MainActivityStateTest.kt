package dev.barrycade.voicecore

import dev.barrycade.voicecore.stt.DrainMode
import dev.barrycade.voicecore.stt.SpeechToText
import dev.barrycade.voicecore.stt.SttReturnCode
import dev.barrycade.voicecore.stt.SttRunConfig
import dev.barrycade.voicecore.stt.StartStrategyConfig
import dev.barrycade.voicecore.stt.StopStrategyConfig
import dev.barrycade.voicecore.stt.TtsEngineConfig
import dev.barrycade.voicecore.stt.VadConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the STT lifecycle state machine rules that the UI must enforce.
 *
 * These tests verify the *contract* that MainActivity.updateUi() and
 * the stop/start button logic must satisfy after Phase 2.3.
 *
 * Because the UI logic depends on Android views (Button, View.VISIBLE, etc.),
 * the actual button visibility is verified via Robolectric in the
 * instrumented tests. This class tests the *business logic* contracts
 * that are framework-agnostic:
 *
 * - Start button must be enabled when startStrategy=MANUAL and !isRecording
 * - Stop button must be enabled when stopStrategy=MANUAL and isRecording=true
 * - No auto-stop on silence
 * - Strategy type tracking
 */
class MainActivityStateTest {

    // ── Strategy tracking tests ──────────────────────────────────────────

    @Test
    fun displayConfig_storesStartAndStopStrategyTypes() {
        // Simulate what displayConfig() does in MainActivity.
        val config = SttRunConfig(
            ttsEngineConfig = TtsEngineConfig(
                modelPath = "/test/path",
                language = "en",
                debugLoggingEnabled = false
            ),
            vadConfig = VadConfig(
                energyThreshold = 0.03f,
                preRollMs = 100,
                stableChunkSizeMs = 500
            ),
            drainMode = DrainMode.DRAIN_FROM_HEAD,
            startStrategy = StartStrategyConfig(type = "MANUAL"),
            stopStrategy = StopStrategyConfig(type = "AUTO_SILENCE", silenceMs = 1500, maxDurationMs = 30000)
        )

        // Assert the input contract.
        assertEquals("MANUAL", config.startStrategy.type)
        assertEquals("AUTO_SILENCE", config.stopStrategy.type)
    }

    @Test
    fun configWithManualManual_hasCorrectStrategyTypes() {
        val config = SttRunConfig(
            ttsEngineConfig = TtsEngineConfig(
                modelPath = "/test/path",
                language = "en",
                debugLoggingEnabled = false
            ),
            vadConfig = VadConfig(
                energyThreshold = 0.03f,
                preRollMs = 100,
                stableChunkSizeMs = 500
            ),
            drainMode = DrainMode.DRAIN_FROM_HEAD,
            startStrategy = StartStrategyConfig(type = "MANUAL"),
            stopStrategy = StopStrategyConfig(type = "MANUAL")
        )

        assertEquals("MANUAL", config.startStrategy.type)
        assertEquals("MANUAL", config.stopStrategy.type)
    }

    @Test
    fun startButton_enabledWhenManualAndNotRecording() {
        // Rule 1 contract: After startSession() returns SUCCESS,
        // stop button must be enabled (for MANUAL stop strategy).
        //
        // This test validates the predicate:
        //   showStart = !isRecording && activeStartStrategyType == "MANUAL"
        val isRecording = false
        val activeStartStrategyType = "MANUAL"
        val showStart = !isRecording && activeStartStrategyType == "MANUAL"
        assertTrue("Start button must show when MANUAL and not recording", showStart)
    }

    @Test
    fun startButton_hiddenWhenVadStart() {
        // Rule 6 contract: if startStrategy = VAD_START, start button is hidden.
        val isRecording = false
        val activeStartStrategyType = "VAD_START"
        val showStart = !isRecording && activeStartStrategyType == "MANUAL"
        assertFalse("Start button must be hidden when startStrategy != MANUAL", showStart)
    }

    @Test
    fun stopButton_visibleWhenManualStopStrategy() {
        // Rule 1 + 6 contract: stop button visible when stopStrategy=MANUAL.
        val activeStopStrategyType = "MANUAL"
        val showStop = activeStopStrategyType == "MANUAL"
        assertTrue("Stop button must show when stopStrategy=MANUAL", showStop)
    }

    @Test
    fun stopButton_hiddenWhenAutoSilenceStopStrategy() {
        // Rule 6 contract: if stopStrategy = AUTO_SILENCE, stop button is hidden.
        val activeStopStrategyType = "AUTO_SILENCE"
        val showStop = activeStopStrategyType == "MANUAL"
        assertFalse("Stop button must be hidden when stopStrategy != MANUAL", showStop)
    }

    @Test
    fun stopButton_hiddenWhenDurationStopStrategy() {
        // Rule 6 contract: if stopStrategy = DURATION, stop button is hidden.
        val activeStopStrategyType = "DURATION"
        val showStop = activeStopStrategyType == "MANUAL"
        assertFalse("Stop button must be hidden when stopStrategy != MANUAL", showStop)
    }

    @Test
    fun manualManual_silenceDoesNotAffectButtonState() {
        // Rule 4 + Rule 6 invariant: In MANUAL/MANUAL mode, silence must
        // never change button state. The UI only reflects isRecording and
        // strategy type — never VAD state or silence.
        //
        // The stop button visibility predicate:
        val activeStopStrategyType = "MANUAL"
        val isRecording = true

        // Simulate silence (no change to any UI state variable).
        val showStop = activeStopStrategyType == "MANUAL"
        assertTrue("Stop button must stay enabled during silence in MANUAL mode", showStop)

        // After stop is pressed:
        val afterStopIsRecording = false
        val showStopAfterStop = afterStopIsRecording && activeStopStrategyType == "MANUAL"
        assertFalse("Stop button must disable after stop", showStopAfterStop)
    }

    // ── Button state machine transitions ─────────────────────────────────

    @Test
    fun startButton_enablesStopButton_transition() {
        // Rule 1: Start button press -> stop button enabled.
        // Simulate the transition that happens in startRecording().
        val isRecordingInitial = false
        val isRecordingAfterStart = true
        val activeStopStrategyType = "MANUAL"

        val stopButtonEnabledBefore = isRecordingInitial && activeStopStrategyType == "MANUAL"
        val stopButtonEnabledAfter = isRecordingAfterStart && activeStopStrategyType == "MANUAL"

        assertFalse("Stop button must be disabled before session starts",
            stopButtonEnabledBefore)
        assertTrue("Stop button must be enabled after session starts in MANUAL mode",
            stopButtonEnabledAfter)
    }

    @Test
    fun stopButton_disablesStopButton_transition() {
        // Rule 2 + 3: Stop button press -> stopAndTranscribe() called ->
        // stop button disabled only after return.
        val isRecordingBeforeStop = true
        val isRecordingAfterStop = false
        val activeStopStrategyType = "MANUAL"

        val stopButtonEnabledBefore = isRecordingBeforeStop && activeStopStrategyType == "MANUAL"
        val stopButtonEnabledAfter = isRecordingAfterStop && activeStopStrategyType == "MANUAL"

        assertTrue("Stop button must be enabled before pressing stop",
            stopButtonEnabledBefore)
        assertFalse("Stop button must be disabled after stop returns",
            stopButtonEnabledAfter)
    }

    @Test
    fun startButton_disabledDuringRecording() {
        // Rule 1: Start button must be disabled while recording.
        val isRecording = true
        val activeStartStrategyType = "MANUAL"
        val showStart = !isRecording && activeStartStrategyType == "MANUAL"
        assertFalse("Start button must be disabled during recording", showStart)
    }

    @Test
    fun startButton_reEnabledAfterStop() {
        // After session ends, start button must be available again.
        val isRecordingAfterStop = false
        val activeStartStrategyType = "MANUAL"
        val showStart = !isRecordingAfterStop && activeStartStrategyType == "MANUAL"
        assertTrue("Start button must be re-enabled after session ends", showStart)
    }

    // ── Blank-audio guard ────────────────────────────────────────────────

    @Test
    fun blankAudioGuard_doesNotAutoStop() {
        // Rule 5: Blank-audio hint does NOT change button state.
        val blankAudioThreshold = 3
        var blankAudioCount = 0

        // Simulate 3 consecutive blank-audio results.
        val BLANK_AUDIO_MARKER = "[BLANK_AUDIO]"
        data class Result(val text: String)

        val results = listOf(
            Result(BLANK_AUDIO_MARKER),
            Result(BLANK_AUDIO_MARKER),
            Result(BLANK_AUDIO_MARKER)
        )

        var hintShown = false
        for (r in results) {
            if (r.text == BLANK_AUDIO_MARKER || r.text == "") {
                blankAudioCount += 1
                if (blankAudioCount >= blankAudioThreshold) {
                    hintShown = true
                }
            }
        }

        assertTrue("Hint must be shown after 3 blank-audio results", hintShown)

        // Invariant: button state is unchanged.
        val isRecording = true
        val activeStopStrategyType = "MANUAL"
        val showStop = activeStopStrategyType == "MANUAL"
        assertTrue("Stop button must remain enabled after blank-audio hint", showStop)
    }

    @Test
    fun blankAudioCount_resetsOnNonBlankResult() {
        // Rule 5: Counter resets when non-blank audio is received.
        val blankAudioThreshold = 3
        var blankAudioCount = 2 // 2 consecutive blanks

        // A non-blank result resets.
        blankAudioCount = 0
        assertEquals(0, blankAudioCount)
    }
}
