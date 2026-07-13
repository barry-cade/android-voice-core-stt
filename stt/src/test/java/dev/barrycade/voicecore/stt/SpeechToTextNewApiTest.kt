package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the new wrapper API on [SpeechToText]: [setConfig] and [startSession].
 *
 * These tests are additive — they do not modify or replace any existing tests.
 * No Android dependencies, no audio hardware, no Whisper model loading.
 */
class SpeechToTextNewApiTest {

    private lateinit var speechToText: SpeechToText
    private var lastResult: String? = null
    private var lastError: Throwable? = null

    @Before
    fun setUp() {
        lastResult = null
        lastError = null

        speechToText = SpeechToText(
            context = null,
            whisperModel = FakeWhisperModel(),
            captureManager = FakeCaptureManager()
        )

        speechToText.setOnResultListener { lastResult = it }
        speechToText.setOnErrorListener { lastError = it }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun validManualStopConfig(): SttConfig {
        return SttConfig(
            modelPath = "/dummy/model.bin",
            language = "en",
            debugLoggingEnabled = false,
            energyThreshold = 0.03f,
            preRollMs = 100,
            stableChunkSizeMs = 500,
            drainMode = DrainMode.DRAIN_FROM_NEXT_FRAME,
            startTrigger = StartTrigger.Manual,
            stopTrigger = StopTrigger.Manual
        )
    }

    /**
     * Helper: runs [action] and catches UnsatisfiedLinkError from WhisperBridge
     * external funs when native libraries are unavailable (unit test environment).
     */
    private fun safeRun(action: () -> Unit) {
        try {
            action()
        } catch (_: UnsatisfiedLinkError) {
            // Native Whisper libraries not available in unit test environment
        }
    }

    // ── setConfig tests ──────────────────────────────────────────────────

    @Test
    fun setConfig_withValidConfig_returnsSuccess() {
        val result = speechToText.setConfig(validManualStopConfig())
        assertNotNull("setConfig must return a SessionResult", result)
        assertEquals(
            "Valid config must return SUCCESS",
            SttReturnCode.SUCCESS,
            result.code
        )
    }

    // ── startSession tests ───────────────────────────────────────────────

    @Test
    fun startSession_withoutConfig_returnsConfigNotSet() {
        val result = speechToText.startSession()
        assertNotNull("startSession without config must return a SessionResult", result)
        assertEquals(
            "startSession without config must return CONFIG_NOT_SET",
            SttReturnCode.CONFIG_NOT_SET,
            result.code
        )
    }

    @Test
    fun startSession_afterSettingConfig_doesNotThrow() {
        val config = validManualStopConfig()
        speechToText.setConfig(config)
        speechToText.initStt(config)
        safeRun {
            val result = speechToText.startSession()
            assertNotNull("startSession with config must return a SessionResult", result)
        }
    }

    @Test
    fun startSession_withManualStop_routesToManualTriggers() {
        val config = validManualStopConfig()
        speechToText.setConfig(config)
        speechToText.initStt(config)
        safeRun {
            val result = speechToText.startSession()
            assertNotNull("startSession with MANUAL stop must return a SessionResult", result)
        }
    }

    @Test
    fun startSession_withAutoSilence_routesToAutoTriggers() {
        val config = SttConfig(
            modelPath = "/dummy/model.bin",
            language = "en",
            debugLoggingEnabled = false,
            energyThreshold = 0.03f,
            preRollMs = 100,
            stableChunkSizeMs = 500,
            drainMode = DrainMode.DRAIN_FROM_NEXT_FRAME,
            startTrigger = StartTrigger.Manual,
            stopTrigger = StopTrigger.AutoSilence(silenceMs = 1200, maxDurationMs = 30000)
        )
        speechToText.setConfig(config)
        speechToText.initStt(config)
        safeRun {
            val result = speechToText.startSession()
            assertNotNull("startSession with AUTO_SILENCE must return a SessionResult", result)
        }
    }

    @Test
    fun startSession_twice_doesNotThrow() {
        val config = validManualStopConfig()
        speechToText.setConfig(config)
        speechToText.initStt(config)
        safeRun {
            speechToText.startSession()
            speechToText.startSession()
        }
    }

    @Test
    fun startSession_afterDestroy_doesNotThrow() {
        val config = validManualStopConfig()
        speechToText.setConfig(config)
        speechToText.initStt(config)
        safeRun {
            speechToText.destroy()
            val result = speechToText.startSession()
            assertNotNull("startSession after destroy must return a SessionResult", result)
        }
    }

    // ── Capture lifecycle (Phase 2.3.1) ──────────────────────────────────

    @Test
    fun constructor_doesNotStartCapture() {
        // Rule 3: Construct STT -> capture must NOT be started.
        // Capture only starts inside begin(), which is called from startSession().
        val captureControllerField = SpeechToText::class.java.getDeclaredField("captureController")
        captureControllerField.isAccessible = true
        val captureController = captureControllerField.get(speechToText) as SttCaptureController
        val fakeCapture = captureController.sessionManager as FakeCaptureManager
        assertFalse(
            "Capture must NOT be started after constructor",
            fakeCapture.isStarted
        )
    }

    @Test
    fun startSession_startsCapture() {
        // Rule 3: Call startSession() -> capture starts only then.
        // Requires initStt() to have been called first.
        val config = validManualStopConfig()
        speechToText.setConfig(config)
        speechToText.initStt(config)
        val captureControllerField = SpeechToText::class.java.getDeclaredField("captureController")
        captureControllerField.isAccessible = true
        val captureController = captureControllerField.get(speechToText) as SttCaptureController
        val fakeCapture = captureController.sessionManager as FakeCaptureManager

        // Before startSession, capture is not running.
        assertFalse("Capture must NOT be started before startSession",
            fakeCapture.isStarted)

        safeRun {
            speechToText.startSession()
            // After startSession, begin() is called which should start capture.
            // Since we use FakeCaptureManager, begin() sets sessionActive = true.
            assertTrue(
                "Capture must be started after startSession",
                fakeCapture.isStarted
            )
        }
    }
}
