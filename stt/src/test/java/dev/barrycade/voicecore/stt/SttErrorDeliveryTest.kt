package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for error delivery through the new JSON API.
 *
 * Validates that every error path produces a correct JSON error string.
 *
 * Uses fake implementations so no Android or native dependencies are required.
 */
class SttErrorDeliveryTest {

    private lateinit var speechToText: SpeechToText
    private lateinit var captureManager: FakeCaptureManager

    @Before
    fun setUp() {
        captureManager = FakeCaptureManager()

        speechToText = SpeechToText(
            whisperModel = FakeWhisperModel(),
            captureManager = captureManager
        )
    }

    private fun buildConfigJson(
        modelPath: String = "/dummy/model.bin",
        stopType: String = "MANUAL"
    ): String {
        return """{"modelPath":"$modelPath","language":"en","debugLoggingEnabled":false,"energyThreshold":0.03,"preRollMs":100,"stableChunkSizeMs":500,"drainMode":"DRAIN_FROM_NEXT_FRAME","startType":"MANUAL","stopType":"$stopType","warmupEnabled":false,"warmupDurationMs":0}"""
    }

    // ── Config parse failure ────────────────────────────────────────────

    @Test
    fun configParseFailure_returnsErrorJson() {
        val result = speechToText.init("{}")

        assertNotNull("init must return error for invalid config", result)
        assertTrue("error JSON must contain type=error", result.contains("\"type\":\"error\""))
        assertTrue("error JSON must contain code=CONFIG_PARSE_FAILED", result.contains("\"code\":\"CONFIG_PARSE_FAILED\""))
        assertTrue("error JSON must contain category=CONFIG_ERROR", result.contains("\"category\":\"CONFIG_ERROR\""))
    }

    // ── Model load failure ──────────────────────────────────────────────

    @Test
    fun modelLoadFailure_returnsErrorJson() {
        val stt = SpeechToText(
            whisperModel = FakeWhisperModel().apply { failOnLoad = true },
            captureManager = FakeCaptureManager()
        )

        // loadModel is internal — call init() which will fail at model load
        val result = stt.init(buildConfigJson())

        assertNotNull("init must return error on model load failure", result)
        assertTrue("error JSON must contain type=error", result.contains("\"type\":\"error\""))
        assertTrue("error JSON must contain code=MODEL_LOAD_FAILED", result.contains("\"code\":\"MODEL_LOAD_FAILED\""))
        assertTrue("error JSON must contain category=WHISPER_ERROR", result.contains("\"category\":\"WHISPER_ERROR\""))
    }

    // ── JSON error message structure verification ───────────────────────

    @Test
    fun errorJson_containsAllRequiredFields() {
        val result = speechToText.init("{}")

        assertNotNull("init must return error", result)

        // Verify all required fields present
        assertTrue("JSON must contain type", result.contains("\"type\""))
        assertTrue("JSON must contain code", result.contains("\"code\""))
        assertTrue("JSON must contain message", result.contains("\"message\""))
        assertTrue("JSON must contain category", result.contains("\"category\""))

        assertTrue("type must be error", result.contains("\"type\":\"error\""))
        assertTrue("code must be non-empty", result.matches(Regex(".*\"code\"\\s*:\\s*\"[^\"]+\".*")))
        assertTrue("message must be non-empty", result.matches(Regex(".*\"message\"\\s*:\\s*\"[^\"]+\".*")))
        assertTrue("category must be non-empty", result.matches(Regex(".*\"category\"\\s*:\\s*\"[^\"]+\".*")))
    }
}
