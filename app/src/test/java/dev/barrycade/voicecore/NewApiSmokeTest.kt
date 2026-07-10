package dev.barrycade.voicecore

import dev.barrycade.voicecore.stt.DrainMode
import dev.barrycade.voicecore.stt.ManualAutoSpecific
import dev.barrycade.voicecore.stt.ManualManualSpecific
import dev.barrycade.voicecore.stt.SessionResult
import dev.barrycade.voicecore.stt.SpeechToText
import dev.barrycade.voicecore.stt.SttLifeCycleStrategy
import dev.barrycade.voicecore.stt.SttReturnCode
import dev.barrycade.voicecore.stt.SttRunConfig
import dev.barrycade.voicecore.stt.TtsEngineConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Smoke test for the new [SttRunConfig]-based API path.
 *
 * Verifies that:
 * - [SttRunConfig] can be constructed with valid values
 * - [SpeechToText.create] returns a valid instance
 * - [SpeechToText.setConfig] with valid config returns SUCCESS
 * - [SpeechToText.setConfig] with invalid config returns INVALID_CONFIG
 */
class NewApiSmokeTest {

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

    private fun validManualAutoConfig(): SttRunConfig {
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

    @Test
    fun runConfig_manualManual_constructsSuccessfully() {
        val config = validManualManualConfig()
        assertNotNull(config)
        assertEquals(SttLifeCycleStrategy.MANUAL_MANUAL, config.ttsLifeCycleStrategy)
    }

    @Test
    fun runConfig_manualAuto_constructsSuccessfully() {
        val config = validManualAutoConfig()
        assertNotNull(config)
        assertEquals(SttLifeCycleStrategy.MANUAL_AUTO, config.ttsLifeCycleStrategy)
    }

    @Test
    fun speechToText_create_returnsInstance() {
        val stt = SpeechToText.create("/dummy/model.bin")
        assertNotNull(stt)
    }

    @Test
    fun speechToText_setConfig_valid_returnsSuccess() {
        val stt = SpeechToText.create("/dummy/model.bin")
        val result = stt.setConfig(validManualManualConfig())
        assertEquals(SttReturnCode.SUCCESS, result.code)
    }

    @Test
    fun speechToText_setConfig_invalidModelPath_returnsInvalidConfig() {
        val stt = SpeechToText.create("/dummy/model.bin")
        val config = SttRunConfig(
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
        val result = stt.setConfig(config)
        assertEquals(SttReturnCode.INVALID_CONFIG, result.code)
    }
}