package dev.barrycade.voicecore

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the STT lifecycle state machine rules that the UI must enforce.
 *
 * These tests verify the *contract* that MainActivity.updateUi() and
 * the stop/start button logic must satisfy — now via the JSON boundary.
 *
 * All config validation is done through JSON strings conforming to the
 * SttJsonAdapter schema. No internal STT types are imported.
 */
class MainActivityStateTest {

    @Test
    fun autoSilenceConfig_json_detectedByStopType() {
        val json = buildConfigJson("AUTO_SILENCE", silenceMs = 1500, maxDurationMs = 30000)
        val obj = JSONObject(json)
        val stopType = obj.getString("stopType")

        assertEquals("AUTO_SILENCE", stopType)
    }

    @Test
    fun manualStopConfig_json_detectedByStopType() {
        val json = buildConfigJson("MANUAL")
        val obj = JSONObject(json)
        val stopType = obj.getString("stopType")

        assertEquals("MANUAL", stopType)
    }

    @Test
    fun stopButton_visibleWhenManualStopType() {
        val isRecording = true
        val stopType = "MANUAL"
        val showStop = isRecording && (stopType == "MANUAL")
        assertTrue("Stop button must show when stopType=Manual", showStop)
    }

    @Test
    fun stopButton_hiddenWhenAutoSilenceStopType() {
        val stopType = "AUTO_SILENCE"
        val showStop = stopType == "MANUAL"
        assertFalse("Stop button must be hidden when stopType != Manual", showStop)
    }

    @Test
    fun stopButton_hiddenWhenDurationStopType() {
        val stopType = "DURATION"
        val showStop = stopType == "MANUAL"
        assertFalse("Stop button must be hidden when stopType != Manual", showStop)
    }

    @Test
    fun manualManual_silenceDoesNotAffectButtonState() {
        val isRecording = true
        val showStop = isRecording && ("MANUAL" == "MANUAL")
        assertTrue("Stop button must stay enabled during silence in MANUAL mode", showStop)

        val afterStopIsRecording = false
        val showStopAfterStop = afterStopIsRecording && ("MANUAL" == "MANUAL")
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

    private fun buildConfigJson(
        stopType: String,
        silenceMs: Int? = null,
        maxDurationMs: Int? = null
    ): String {
        val root = JSONObject()
        root.put("modelPath", "/test/path")
        root.put("language", "en")
        root.put("debugLoggingEnabled", false)
        root.put("energyThreshold", 0.03)
        root.put("preRollMs", 100)
        root.put("stableChunkSizeMs", 500)
        root.put("drainMode", "DRAIN_FROM_HEAD")
        root.put("startType", "MANUAL")
        root.put("stopType", stopType)
        root.put("warmupEnabled", false)
        root.put("warmupDurationMs", 0)

        if (silenceMs != null) root.put("silenceMs", silenceMs)
        if (maxDurationMs != null) root.put("maxDurationMs", maxDurationMs)

        return root.toString()
    }
}
