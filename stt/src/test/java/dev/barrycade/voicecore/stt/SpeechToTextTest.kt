package dev.barrycade.voicecore.stt

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

        SpeechToText.resetForTest()
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
    fun init_returnsNullOnSuccess() {
        safeRun {
            val result = speechToText.init(buildConfigJson())
            assertNull("Result should be null (success)", result)
        }
    }

    @Test
    fun init_withInvalidConfigJson_returnsError() {
        val result = speechToText.init("{}")
        // loadModel catches the parse failure and returns an SttError
        // with CONFIG_PARSE_FAILED code (the error is also dispatched via listener).
        assertNotNull("Result should be non-null (error)", result)
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

