package dev.barrycade.voicecore

import dev.barrycade.voicecore.stt.DrainMode
import dev.barrycade.voicecore.stt.StartStrategyConfig
import dev.barrycade.voicecore.stt.StopStrategyConfig
import dev.barrycade.voicecore.stt.SttRunConfig
import dev.barrycade.voicecore.stt.TtsEngineConfig
import dev.barrycade.voicecore.stt.VadConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Smoke test for [SttRunConfig] construction and field defaults.
 *
 * Tests that:
 * - [SttRunConfig] can be constructed with valid values
 * - Field accessors return expected values
 * - [DrainMode], [StartStrategyConfig], [StopStrategyConfig]
 *   wire correctly into the config object
 *
 * These tests do NOT require Android SDK (no AudioRecord etc).
 */
class NewApiSmokeTest {

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

    private fun validAutoSilenceConfig(): SttRunConfig {
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
            stopStrategy = StopStrategyConfig(
                type = "AUTO_SILENCE",
                silenceMs = 1200,
                maxDurationMs = 30000
            )
        )
    }

    @Test
    fun runConfig_manualStop_constructsSuccessfully() {
        val config = validManualStopConfig()
        assertNotNull(config)
        assertEquals("MANUAL", config.stopStrategy.type)
    }

    @Test
    fun runConfig_autoSilence_constructsSuccessfully() {
        val config = validAutoSilenceConfig()
        assertNotNull(config)
        assertEquals("AUTO_SILENCE", config.stopStrategy.type)
    }

    @Test
    fun runConfig_manualStop_hasExpectedVadConfig() {
        val config = validManualStopConfig()
        assertEquals(0.03f, config.vadConfig.energyThreshold, 0.001f)
        assertEquals(100, config.vadConfig.preRollMs)
        assertEquals(500, config.vadConfig.stableChunkSizeMs)
    }
}
