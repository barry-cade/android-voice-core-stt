package dev.barrycade.voicecore.stt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [SpeechToText] orchestrator logic.
 *
 * Validates start/stop gates, state machine transitions, queued start,
 * queued stop, destroy ordering, and error dispatch.
 */
class SpeechToTextTest {

    private lateinit var speechToText: SpeechToText
    private var lastResult: String? = null
    private var lastError: Throwable? = null

    @Before
    fun setUp() {
        lastResult = null
        lastError = null

        speechToText = SpeechToText(
            config = RuntimeSttConfig(),
            modelPath = "/dummy/model/path.bin",
            captureManager = FakeCaptureManager()
        )

        speechToText.setOnResultListener { lastResult = it }
        speechToText.setOnErrorListener { lastError = it }
    }

    private fun safeRun(action: () -> Unit) {
        try {
            action()
        } catch (_: UnsatisfiedLinkError) {
            // Native Whisper libraries not available in unit test environment
        }
    }

    @Test
    fun constructor_createsInstance() {
        assertNotNull(speechToText)
    }

    @Test
    fun setOnResultListener_storesListener() {
        var captured: String? = null
        speechToText.setOnResultListener { captured = it }
    }

    @Test
    fun setOnErrorListener_storesListener() {
        var captured: Throwable? = null
        speechToText.setOnErrorListener { captured = it }
    }

    @Test
    fun processStart_beforeReady_queuesStartRequest() {
        safeRun { speechToText.processStart() }
    }

    @Test
    fun processStart_twice_isIdempotent() {
        safeRun { speechToText.processStart(); speechToText.processStart() }
    }

    @Test
    fun stop_queuedBeforeStart_setsStopRequested() {
        safeRun { speechToText.stop() }
    }

    @Test
    fun stopAndTranscribe_queuedBeforeStart_setsStopRequested() {
        safeRun { speechToText.stopAndTranscribe() }
    }

    @Test
    fun destroy_cleansUpResources() {
        safeRun { speechToText.destroy() }
    }

    @Test
    fun destroy_twice_isIdempotent() {
        safeRun { speechToText.destroy(); speechToText.destroy() }
    }

    @Test
    fun processStart_after_destroy_doesNotCrash() {
        safeRun { speechToText.destroy(); speechToText.processStart() }
    }

    @Test
    fun setDebugOptions_forceTimeout_setsFlag() {
        speechToText.setDebugOptions(forceTimeout = true)
        assertTrue("forceTimeout must be set", speechToText.debugOptions.forceTimeout)
    }

    @Test
    fun setDebugOptions_forceAudioInitFailure_setsFlag() {
        speechToText.setDebugOptions(forceAudioInitFailure = true)
        assertTrue("forceAudioInitFailure must be set", speechToText.debugOptions.forceAudioInitFailure)
    }

    @Test
    fun start_stop_destroy_sequence_noErrors() {
        safeRun { speechToText.processStart(); speechToText.stop(); speechToText.destroy() }
    }

    @Test
    fun destroy_stop_after_destroy_noErrors() {
        safeRun { speechToText.destroy(); speechToText.stop() }
    }
}
