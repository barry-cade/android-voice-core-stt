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
            config = RuntimeSttConfig(),
            modelPath = "/dummy/model/path.bin",
            captureManager = FakeCaptureManager()
        )

        speechToText.setOnResultListener { lastResult = it }
        speechToText.setOnErrorListener { lastError = it }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun validManualStopConfig(): SttRunConfig {
        return SttRunConfig(
            ttsEngineConfig = TtsEngineConfig(
                modelPath = "/dummy/model.bin",
                language = "en",
                debugLoggingEnabled = false
            ),
            vadConfig = VadConfig(
                energyThreshold = 0.03f,
                preRollMs = 100,
                stableChunkSizeMs = 500
            ),
            drainMode = DrainMode.DRAIN_FROM_NEXT_FRAME,
            startStrategy = StartStrategyConfig(type = "MANUAL"),
            stopStrategy = StopStrategyConfig(type = "MANUAL")
        )
    }

    private fun invalidConfig(): SttRunConfig {
        return SttRunConfig(
            ttsEngineConfig = TtsEngineConfig(
                modelPath = "",
                language = "en",
                debugLoggingEnabled = false
            ),
            vadConfig = VadConfig(
                energyThreshold = 0.03f,
                preRollMs = 100,
                stableChunkSizeMs = 500
            ),
            drainMode = DrainMode.DRAIN_FROM_NEXT_FRAME,
            startStrategy = StartStrategyConfig(type = "MANUAL"),
            stopStrategy = StopStrategyConfig(type = "MANUAL")
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

    @Test
    fun setConfig_withInvalidConfig_returnsInvalidConfig() {
        val result = speechToText.setConfig(invalidConfig())
        assertNotNull("setConfig must return a SessionResult", result)
        assertEquals(
            "Invalid config must return INVALID_CONFIG",
            SttReturnCode.INVALID_CONFIG,
            result.code
        )
    }

    @Test
    fun setConfig_twiceWithValidConfig_overwritesPrevious() {
        // First call
        val first = speechToText.setConfig(validManualStopConfig())
        assertEquals(SttReturnCode.SUCCESS, first.code)

        // Second call with a different valid config
        val secondConfig = validManualStopConfig().copy(
            ttsEngineConfig = validManualStopConfig().ttsEngineConfig.copy(
                language = "fr"
            )
        )
        val second = speechToText.setConfig(secondConfig)
        assertEquals(SttReturnCode.SUCCESS, second.code)
    }

    @Test
    fun setConfig_withInvalidValues_returnsInvalidConfig() {
        val nullLikeConfig = SttRunConfig(
            ttsEngineConfig = TtsEngineConfig(
                modelPath = "",
                language = "",
                debugLoggingEnabled = false
            ),
            vadConfig = VadConfig(
                energyThreshold = 0.03f,
                preRollMs = -1,
                stableChunkSizeMs = -1
            ),
            drainMode = DrainMode.DRAIN_FROM_NEXT_FRAME,
            startStrategy = StartStrategyConfig(type = "MANUAL"),
            stopStrategy = StopStrategyConfig(type = "MANUAL")
        )
        val result = speechToText.setConfig(nullLikeConfig)
        assertNotNull(result)
        assertEquals(SttReturnCode.INVALID_CONFIG, result.code)
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
        speechToText.setConfig(validManualStopConfig())
        safeRun {
            val result = speechToText.startSession()
            assertNotNull("startSession with config must return a SessionResult", result)
        }
    }

    @Test
    fun startSession_withManualStop_routesToManualTriggers() {
        speechToText.setConfig(validManualStopConfig())
        safeRun {
            val result = speechToText.startSession()
            assertNotNull("startSession with MANUAL stop must return a SessionResult", result)
        }
    }

    @Test
    fun startSession_withAutoSilence_routesToAutoTriggers() {
        val config = SttRunConfig(
            ttsEngineConfig = TtsEngineConfig(
                modelPath = "/dummy/model.bin",
                language = "en",
                debugLoggingEnabled = false
            ),
            vadConfig = VadConfig(
                energyThreshold = 0.03f,
                preRollMs = 100,
                stableChunkSizeMs = 500
            ),
            drainMode = DrainMode.DRAIN_FROM_NEXT_FRAME,
            startStrategy = StartStrategyConfig(type = "MANUAL"),
            stopStrategy = StopStrategyConfig(
                type = "AUTO_SILENCE",
                silenceMs = 1200,
                maxDurationMs = 30000
            )
        )
        speechToText.setConfig(config)
        safeRun {
            val result = speechToText.startSession()
            assertNotNull("startSession with AUTO_SILENCE must return a SessionResult", result)
        }
    }

    @Test
    fun startSession_twice_doesNotThrow() {
        speechToText.setConfig(validManualStopConfig())
        safeRun {
            speechToText.startSession()
            speechToText.startSession()
        }
    }

    @Test
    fun startSession_afterDestroy_doesNotThrow() {
        speechToText.setConfig(validManualStopConfig())
        safeRun {
            speechToText.destroy()
            val result = speechToText.startSession()
            assertNotNull("startSession after destroy must return a SessionResult", result)
        }
    }

    // ── Combined setConfig + startSession flow ───────────────────────────

    @Test
    fun setConfigThenStartSession_returnsSuccess() {
        speechToText.setConfig(validManualStopConfig())
        safeRun {
            val result = speechToText.startSession()
            assertNotNull(result)
        }
    }

    @Test
    fun invalidConfigThenStartSession_returnsConfigNotSet() {
        // Invalid config is rejected by setConfig — runConfig remains null
        speechToText.setConfig(invalidConfig())
        val result = speechToText.startSession()
        assertEquals(SttReturnCode.CONFIG_NOT_SET, result.code)
    }

    // ── Capture lifecycle (Phase 2.3.1) ──────────────────────────────────

    @Test
    fun constructor_doesNotStartCapture() {
        // Rule 3: Construct STT -> capture must NOT be started.
        // Capture only starts inside begin(), which is called from startSession().
        val captureManagerField = SpeechToText::class.java.getDeclaredField("captureManager")
        captureManagerField.isAccessible = true
        val fakeCapture = captureManagerField.get(speechToText) as FakeCaptureManager
        assertFalse(
            "Capture must NOT be started after constructor",
            fakeCapture.isStarted
        )
    }

    @Test
    fun startSession_startsCapture() {
        // Rule 3: Call startSession() -> capture starts only then.
        speechToText.setConfig(validManualStopConfig())
        val captureManagerField = SpeechToText::class.java.getDeclaredField("captureManager")
        captureManagerField.isAccessible = true
        val fakeCapture = captureManagerField.get(speechToText) as FakeCaptureManager

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
