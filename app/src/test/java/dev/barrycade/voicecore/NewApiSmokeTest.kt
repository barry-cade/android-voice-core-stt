package dev.barrycade.voicecore

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smoke test for the JSON boundary config construction.
 *
 * Tests that JSON config strings are correctly structured
 * according to the SttJsonAdapter schema. These tests do NOT
 * require Android SDK or STT module internals.
 */
class NewApiSmokeTest {

    private fun buildConfigJson(
        modelPath: String,
        stopType: String,
        energyThreshold: Double = 0.03,
        preRollMs: Int = 100,
        stableChunkSizeMs: Int = 500
    ): String {
        val root = JSONObject()
        root.put("modelPath", modelPath)
        root.put("language", "en")
        root.put("debugLoggingEnabled", false)
        root.put("energyThreshold", energyThreshold)
        root.put("preRollMs", preRollMs)
        root.put("stableChunkSizeMs", stableChunkSizeMs)
        root.put("drainMode", "DRAIN_FROM_NEXT_FRAME")
        root.put("startType", "MANUAL")
        root.put("stopType", stopType)
        root.put("warmupEnabled", false)
        root.put("warmupDurationMs", 0)
        return root.toString()
    }

    @Test
    fun manualStopJson_containsRequiredFields() {
        val json = buildConfigJson("/dummy/model.bin", "MANUAL")
        val obj = JSONObject(json)

        assertEquals("/dummy/model.bin", obj.getString("modelPath"))
        assertEquals("MANUAL", obj.getString("stopType"))
        assertEquals("MANUAL", obj.getString("startType"))
        assertEquals("DRAIN_FROM_NEXT_FRAME", obj.getString("drainMode"))
        assertEquals(0.03, obj.getDouble("energyThreshold"), 0.001)
        assertEquals(100, obj.getInt("preRollMs"))
        assertEquals(500, obj.getInt("stableChunkSizeMs"))
        assertFalse(obj.has("silenceMs"))
        assertFalse(obj.has("maxDurationMs"))
    }

    @Test
    fun autoSilenceJson_containsExtraFields() {
        val json = buildConfigJson("/dummy/model.bin", "AUTO_SILENCE")
        val obj = JSONObject(json)

        // First add the auto-silence specific fields as the app would
        obj.put("silenceMs", 1200)
        obj.put("maxDurationMs", 30000)

        assertEquals("AUTO_SILENCE", obj.getString("stopType"))
        assertEquals(1200, obj.getInt("silenceMs"))
        assertEquals(30000, obj.getInt("maxDurationMs"))
    }

    @Test
    fun manualStopJson_hasExpectedVadConfig() {
        val json = buildConfigJson("/dummy/model.bin", "MANUAL", 0.03, 100, 500)
        val obj = JSONObject(json)

        assertEquals(0.03, obj.getDouble("energyThreshold"), 0.001)
        assertEquals(100, obj.getInt("preRollMs"))
        assertEquals(500, obj.getInt("stableChunkSizeMs"))
    }

    @Test
    fun resultJson_parsesCorrectly() {
        val json = """{"type":"result","text":"hello world","code":"SUCCESS","timing":{"vadActiveMs":1200,"utteranceMs":3200,"inferenceMs":450,"totalMs":5200}}"""
        val obj = JSONObject(json)

        assertEquals("result", obj.getString("type"))
        assertEquals("hello world", obj.getString("text"))
        assertEquals("SUCCESS", obj.getString("code"))

        val timing = obj.getJSONObject("timing")
        assertEquals(1200, timing.getLong("vadActiveMs"))
        assertEquals(3200, timing.getLong("utteranceMs"))
        assertEquals(450, timing.getLong("inferenceMs"))
        assertEquals(5200, timing.getLong("totalMs"))
    }

    @Test
    fun errorJson_parsesCorrectly() {
        val json = """{"type":"error","code":"MODEL_LOAD_FAILED","message":"File not found"}"""
        val obj = JSONObject(json)

        assertEquals("error", obj.getString("type"))
        assertEquals("MODEL_LOAD_FAILED", obj.getString("code"))
        assertEquals("File not found", obj.getString("message"))
    }
}
