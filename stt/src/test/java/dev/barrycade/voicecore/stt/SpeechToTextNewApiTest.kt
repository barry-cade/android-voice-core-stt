package dev.barrycade.voicecore.stt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the new JSON-boundary API on [SpeechToText]:
 * [init], [configure], and [transcribe] all return JSON strings.
 *
 * No Android dependencies, no audio hardware, no Whisper model loading.
 */
class SpeechToTextNewApiTest {

    private lateinit var speechToText: SpeechToText

    @Before
    fun setUp() {
        speechToText = SpeechToText(
            whisperModel = FakeWhisperModel(),
            captureManager = FakeCaptureManager()
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun buildConfigJson(
        modelPath: String = "/dummy/model.bin",
        stopType: String = "MANUAL",
        energyThreshold: Double = 0.03,
        preRollMs: Int = 100,
        stableChunkSizeMs: Int = 500
    ): String {
        val sb = StringBuilder()
        sb.append("{\"modelPath\":\"$modelPath\",")
        sb.append("\"language\":\"en\",")
        sb.append("\"debugLoggingEnabled\":false,")
        sb.append("\"energyThreshold\":$energyThreshold,")
        sb.append("\"preRollMs\":$preRollMs,")
        sb.append("\"stableChunkSizeMs\":$stableChunkSizeMs,")
        sb.append("\"drainMode\":\"DRAIN_FROM_NEXT_FRAME\",")
        sb.append("\"startType\":\"MANUAL\",")
        sb.append("\"stopType\":\"$stopType\",")
        sb.append("\"warmupEnabled\":false,")
        sb.append("\"warmupDurationMs\":0")
        sb.append("}")
        return sb.toString()
    }

    // ── init tests ───────────────────────────────────────────────────────

    @Test
    fun init_withValidJson_returnsInitOk() {
        val json = buildConfigJson()
        val result = speechToText.init(json)
        assertTrue("Result should contain init ok", result.contains("\"type\":\"init\"") && result.contains("\"status\":\"ok\""))
    }

    @Test
    fun init_withInvalidJson_returnsError() {
        val invalidJson = """{"bad": "config"}"""
        val result = speechToText.init(invalidJson)
        assertTrue("Result should contain error", result.contains("\"type\":\"error\""))
        assertTrue("Error should be CONFIG_PARSE_FAILED", result.contains("\"code\":\"CONFIG_PARSE_FAILED\""))
    }

    @Test
    fun init_afterInit_returnsInitOkImmediately() {
        val json = buildConfigJson()
        speechToText.init(json)
        val secondResult = speechToText.init(json)
        assertTrue("Second init should return ok", secondResult.contains("\"type\":\"init\"") && secondResult.contains("\"status\":\"ok\""))
    }

    @Test
    fun init_withManualStop_doesNotThrow() {
        val json = buildConfigJson(stopType = "MANUAL")
        val result = speechToText.init(json)
        assertTrue("Init should succeed", result.contains("\"type\":\"init\""))
    }

    @Test
    fun init_withAutoSilence_doesNotThrow() {
        val json = """{"modelPath":"/dummy/model.bin","language":"en","debugLoggingEnabled":false,"energyThreshold":0.03,"preRollMs":100,"stableChunkSizeMs":500,"drainMode":"DRAIN_FROM_NEXT_FRAME","startType":"MANUAL","stopType":"AUTO_SILENCE","silenceMs":1200,"maxDurationMs":30000,"warmupEnabled":false,"warmupDurationMs":0}"""
        val result = speechToText.init(json)
        assertTrue("Init should succeed", result.contains("\"type\":\"init\""))
    }

    @Test
    fun init_twice_doesNotThrow() {
        val json = buildConfigJson()
        speechToText.init(json)
        speechToText.init(json)
    }

    // ── Capture lifecycle ────────────────────────────────────────────────

    @Test
    fun constructor_doesNotStartCapture() {
        // Rule 3: Construct STT -> capture must NOT be started.
        val captureControllerField = SpeechToText::class.java.getDeclaredField("captureController")
        captureControllerField.isAccessible = true
        val captureController = captureControllerField.get(speechToText) as SttCaptureController
        val fakeCapture = captureController.sessionManager as FakeCaptureManager
        assertFalse(
            "Capture must NOT be started after constructor",
            fakeCapture.isStarted
        )
    }
}
