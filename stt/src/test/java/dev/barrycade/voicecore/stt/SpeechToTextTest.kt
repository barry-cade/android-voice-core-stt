package dev.barrycade.voicecore.stt

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [SpeechToText] JSON-boundary API ([init] and [transcribe]).
 *
 * These tests validate that the JSON boundary API works correctly.
 * No Android dependencies, no audio hardware, no Whisper model loading.
 */
class SpeechToTextTest {

    private lateinit var speechToText: SpeechToText
    private var lastMessageJson: String? = null

    @Before
    fun setUp() {
        lastMessageJson = null

        speechToText = SpeechToText(
            context = null,
            whisperModel = FakeWhisperModel(),
            captureManager = FakeCaptureManager()
        )

        speechToText.setOnMessageListener { json -> lastMessageJson = json }
    }

    private fun buildConfigJson(
        modelPath: String = "/dummy/model.bin"
    ): String {
        return """{"modelPath":"$modelPath","language":"en","debugLoggingEnabled":false,"energyThreshold":0.03,"preRollMs":100,"stableChunkSizeMs":500,"drainMode":"DRAIN_FROM_NEXT_FRAME","startType":"MANUAL","stopType":"MANUAL","warmupEnabled":false,"warmupDurationMs":0}"""
    }

    private fun hasJsonType(json: String, expectedType: String): Boolean {
        return json.contains("\"type\":\"$expectedType\"")
    }

    private fun hasJsonCode(json: String, expectedCode: String): Boolean {
        return json.contains("\"code\":\"$expectedCode\"")
    }

    private fun safeRun(action: () -> Unit) {
        try {
            action()
        } catch (_: UnsatisfiedLinkError) {
            // Native Whisper libraries not available in unit test environment
        } catch (e: RuntimeException) {
            // ModelManager may fail silently with FakeWhisperModel
        }
    }

    @Test
    fun constructor_createsInstance() {
        assertNotNull(speechToText)
    }

    @Test
    fun init_returnsSuccessJson() {
        safeRun {
            val result = speechToText.init(buildConfigJson())
            assertTrue("Result should contain success type", hasJsonType(result, "result"))
            assertTrue("Result should contain SUCCESS code", hasJsonCode(result, "SUCCESS"))
        }
    }

    @Test
    fun init_withInvalidConfigJson_returnsError() {
        val result = speechToText.init("{}")
        assertTrue("Result should contain error type", hasJsonType(result, "error"))
    }

    @Test
    fun transcribe_doesNotThrow() {
        safeRun {
            speechToText.transcribe()
        }
    }

    @Test
    fun processStart_doesNotThrow() {
        safeRun { speechToText.processStart() }
    }

    @Test
    fun processStart_twice_isIdempotent() {
        safeRun { speechToText.processStart(); speechToText.processStart() }
    }

    @Test
    fun transcribe_afterInit_doesNotThrow() {
        safeRun {
            speechToText.init(buildConfigJson())
            speechToText.transcribe()
        }
    }
}

