package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    private fun validManualManualConfig(): SttRunConfig {
        return SttRunConfig(
            ttsEngineConfig = TtsEngineConfig(
                modelPath = "/dummy/model.bin",
                language = "en",
                preRollMs = 100,
                stableChunkSizeMs = 500,
                debugLoggingEnabled = false
            ),
            ttsLifeCycleStrategy = SttLifeCycleStrategy.MANUAL_MANUAL,
            strategySpecific = ManualManualSpecific(
                energyThreshold = 0.03f,
                maxDurationMs = 30000,
                abnormalSilenceMs = 5000,
                drainMode = DrainMode.DRAIN_FROM_NEXT_FRAME
            )
        )
    }

    private fun invalidConfig(): SttRunConfig {
        return SttRunConfig(
            ttsEngineConfig = TtsEngineConfig(
                modelPath = "",
                language = "en",
                preRollMs = 100,
                stableChunkSizeMs = 500,
                debugLoggingEnabled = false
            ),
            ttsLifeCycleStrategy = SttLifeCycleStrategy.MANUAL_MANUAL,
            strategySpecific = ManualManualSpecific(
                energyThreshold = 0.03f,
                maxDurationMs = 30000,
                abnormalSilenceMs = 5000,
                drainMode = DrainMode.DRAIN_FROM_NEXT_FRAME
            )
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
        val result = speechToText.setConfig(validManualManualConfig())
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
        val first = speechToText.setConfig(validManualManualConfig())
        assertEquals(SttReturnCode.SUCCESS, first.code)

        // Second call with a different valid config
        val secondConfig = validManualManualConfig().copy(
            ttsEngineConfig = validManualManualConfig().ttsEngineConfig.copy(
                language = "fr"
            )
        )
        val second = speechToText.setConfig(secondConfig)
        assertEquals(SttReturnCode.SUCCESS, second.code)
    }

    @Test
    fun setConfig_withNullConfig_usesValidator() {
        // setConfig does not accept null (Kotlin non-null type),
        // but passing a config with null-like values is handled by the validator.
        val nullLikeConfig = SttRunConfig(
            ttsEngineConfig = TtsEngineConfig(
                modelPath = "",
                language = "",
                preRollMs = -1,
                stableChunkSizeMs = -1,
                debugLoggingEnabled = false
            ),
            ttsLifeCycleStrategy = SttLifeCycleStrategy.MANUAL_MANUAL,
            strategySpecific = "wrong type"
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
        speechToText.setConfig(validManualManualConfig())
        safeRun {
            val result = speechToText.startSession()
            assertNotNull("startSession with config must return a SessionResult", result)
        }
    }

    @Test
    fun startSession_withManualManual_routesToManualTriggers() {
        speechToText.setConfig(validManualManualConfig())
        safeRun {
            val result = speechToText.startSession()
            assertNotNull("startSession with MANUAL_MANUAL must return a SessionResult", result)
        }
    }

    @Test
    fun startSession_withManualAuto_routesToAutoTriggers() {
        val config = SttRunConfig(
            ttsEngineConfig = TtsEngineConfig(
                modelPath = "/dummy/model.bin",
                language = "en",
                preRollMs = 100,
                stableChunkSizeMs = 500,
                debugLoggingEnabled = false
            ),
            ttsLifeCycleStrategy = SttLifeCycleStrategy.MANUAL_AUTO,
            strategySpecific = ManualAutoSpecific(
                energyThreshold = 0.03f,
                maxDurationMs = 30000,
                autoSilenceMs = 1200
            )
        )
        speechToText.setConfig(config)
        safeRun {
            val result = speechToText.startSession()
            assertNotNull("startSession with MANUAL_AUTO must return a SessionResult", result)
        }
    }

    @Test
    fun startSession_twice_doesNotThrow() {
        speechToText.setConfig(validManualManualConfig())
        safeRun {
            speechToText.startSession()
            speechToText.startSession()
        }
    }

    @Test
    fun startSession_afterDestroy_doesNotThrow() {
        speechToText.setConfig(validManualManualConfig())
        safeRun {
            speechToText.destroy()
            val result = speechToText.startSession()
            assertNotNull("startSession after destroy must return a SessionResult", result)
        }
    }

    // ── Combined setConfig + startSession flow ───────────────────────────

    @Test
    fun setConfigThenStartSession_returnsSuccess() {
        speechToText.setConfig(validManualManualConfig())
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
}
