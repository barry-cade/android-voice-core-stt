package dev.barrycade.voicecore

import dev.barrycade.voicecore.stt.ManualAutoSpecific
import dev.barrycade.voicecore.stt.ManualManualSpecific
import dev.barrycade.voicecore.stt.SpeechToText
import dev.barrycade.voicecore.stt.SttConfig
import dev.barrycade.voicecore.stt.SttLifeCycleStrategy
import dev.barrycade.voicecore.stt.SttReturnCode
import dev.barrycade.voicecore.stt.SttRunConfig
import dev.barrycade.voicecore.stt.TtsEngineConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Smoke test for the new [SpeechToText.setConfig] / [SpeechToText.startSession] API.
 *
 * This test validates that the new API path loads, validates, and routes
 * correctly in a pure JVM environment. It does NOT require:
 * - Audio hardware
 * - Whisper model loading
 * - Microphone permissions
 *
 * Uses only the public API — [SpeechToText.create] and new API types.
 */
class NewApiSmokeTest {

    /**
     * Helper: construct a minimal valid [SttRunConfig] for MANUAL_MANUAL mode.
     */
    private fun minimalManualManualConfig(): SttRunConfig {
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
                abnormalSilenceMs = 5000
            )
        )
    }

    /**
     * Helper: construct a minimal valid [SttRunConfig] for MANUAL_AUTO mode.
     */
    private fun minimalManualAutoConfig(): SttRunConfig {
        return SttRunConfig(
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
    }

    /**
     * Helper: runs [action] and catches UnsatisfiedLinkError from WhisperBridge
     * when native libraries are unavailable (pure JVM test environment).
     */
    private fun safeRun(action: () -> Unit) {
        try {
            action()
        } catch (_: UnsatisfiedLinkError) {
            // Native Whisper libraries not available — expected in this environment
        }
    }

    @Test
    fun setConfig_withValidManualManualConfig_returnsSuccess() {
        val stt = SpeechToText.create(
            SttConfig(modelPath = "/dummy/model.bin")
        )
        val result = stt.setConfig(minimalManualManualConfig())
        assertNotNull("setConfig must return a SessionResult", result)
        assertEquals(
            "Valid MANUAL_MANUAL config must return SUCCESS",
            SttReturnCode.SUCCESS,
            result.code
        )
    }

    @Test
    fun setConfig_withValidManualAutoConfig_returnsSuccess() {
        val stt = SpeechToText.create(
            SttConfig(modelPath = "/dummy/model.bin")
        )
        val result = stt.setConfig(minimalManualAutoConfig())
        assertNotNull("setConfig must return a SessionResult", result)
        assertEquals(
            "Valid MANUAL_AUTO config must return SUCCESS",
            SttReturnCode.SUCCESS,
            result.code
        )
    }

    @Test
    fun startSession_withoutConfig_returnsConfigNotSet() {
        val stt = SpeechToText.create(
            SttConfig(modelPath = "/dummy/model.bin")
        )
        val result = stt.startSession()
        assertNotNull("startSession without config must return a SessionResult", result)
        assertEquals(
            "startSession without config must return CONFIG_NOT_SET",
            SttReturnCode.CONFIG_NOT_SET,
            result.code
        )
    }

    @Test
    fun setConfigThenStartSession_withManualManual_doesNotThrow() {
        val stt = SpeechToText.create(
            SttConfig(modelPath = "/dummy/model.bin")
        )
        stt.setConfig(minimalManualManualConfig())
        safeRun {
            val result = stt.startSession()
            assertNotNull("startSession must return a SessionResult", result)
        }
    }

    @Test
    fun setConfigThenStartSession_withManualAuto_doesNotThrow() {
        val stt = SpeechToText.create(
            SttConfig(modelPath = "/dummy/model.bin")
        )
        stt.setConfig(minimalManualAutoConfig())
        safeRun {
            val result = stt.startSession()
            assertNotNull("startSession must return a SessionResult", result)
        }
    }
}
