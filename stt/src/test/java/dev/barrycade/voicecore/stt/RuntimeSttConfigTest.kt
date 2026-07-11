package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Tests for [RuntimeSttConfig] validation.
 *
 * Removed: manualManual/manualAuto-specific range tests since those fields
 * are now consolidated into [manualStopMode], [autoSilenceMs], [autoMaxDurationMs].
 * Validation now covers energy, preRollMs, and stableChunkSizeMs only.
 */
class RuntimeSttConfigTest {

    @Test
    fun validate_defaultConfig_succeeds() {
        val config = RuntimeSttConfig()
        config.validate()
    }

    @Test
    fun validate_energyThresholdLowBound_succeeds() {
        val config = RuntimeSttConfig(energyThreshold = 0.0001f)
        config.validate()
    }

    @Test
    fun validate_energyThresholdHighBound_succeeds() {
        val config = RuntimeSttConfig(energyThreshold = 1f)
        config.validate()
    }

    @Test
    fun validate_energyThresholdBelowMinimum_throws() {
        val config = RuntimeSttConfig(energyThreshold = 0f)
        assertThrows(IllegalArgumentException::class.java) { config.validate() }
    }

    @Test
    fun validate_energyThresholdAboveMaximum_throws() {
        val config = RuntimeSttConfig(energyThreshold = 1.1f)
        assertThrows(IllegalArgumentException::class.java) { config.validate() }
    }

    @Test
    fun validate_preRollMsLowBound_succeeds() {
        val config = RuntimeSttConfig(preRollMs = 0)
        config.validate()
    }

    @Test
    fun validate_preRollMsHighBound_succeeds() {
        val config = RuntimeSttConfig(preRollMs = 2000)
        config.validate()
    }

    @Test
    fun validate_preRollMsAboveMaximum_throws() {
        val config = RuntimeSttConfig(preRollMs = 2001)
        assertThrows(IllegalArgumentException::class.java) { config.validate() }
    }

    @Test
    fun validate_stableChunkSizeMsLowBound_succeeds() {
        val config = RuntimeSttConfig(stableChunkSizeMs = 50)
        config.validate()
    }

    @Test
    fun validate_stableChunkSizeMsHighBound_succeeds() {
        val config = RuntimeSttConfig(stableChunkSizeMs = 2000)
        config.validate()
    }

    @Test
    fun validate_stableChunkSizeMsAboveMaximum_throws() {
        val config = RuntimeSttConfig(stableChunkSizeMs = 2001)
        assertThrows(IllegalArgumentException::class.java) { config.validate() }
    }

    // ── fromSttRunConfig smoke test ──────────────────────────────────────

    @Test
    fun fromSttRunConfig_manualStop_populatesCorrectly() {
        val runConfig = SttRunConfig(
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
        val runtime = RuntimeSttConfig.fromSttRunConfig(runConfig)
        assertEquals(0.03f, runtime.energyThreshold, 0.001f)
        assertEquals(100, runtime.preRollMs)
        assertEquals(500, runtime.stableChunkSizeMs)
        assertEquals(true, runtime.manualStopMode)
    }

    @Test
    fun fromSttRunConfig_autoSilence_populatesCorrectly() {
        val runConfig = SttRunConfig(
            ttsEngineConfig = TtsEngineConfig(
                modelPath = "/dummy/model.bin",
                language = "en",
                debugLoggingEnabled = false
            ),
            vadConfig = VadConfig(
                energyThreshold = 0.05f,
                preRollMs = 200,
                stableChunkSizeMs = 600
            ),
            drainMode = DrainMode.DRAIN_FROM_NEXT_FRAME,
            startStrategy = StartStrategyConfig(type = "MANUAL"),
            stopStrategy = StopStrategyConfig(
                type = "AUTO_SILENCE",
                silenceMs = 1500,
                maxDurationMs = 40000
            )
        )
        val runtime = RuntimeSttConfig.fromSttRunConfig(runConfig)
        assertEquals(0.05f, runtime.energyThreshold, 0.001f)
        assertEquals(200, runtime.preRollMs)
        assertEquals(600, runtime.stableChunkSizeMs)
        assertEquals(false, runtime.manualStopMode)
        assertEquals(1500, runtime.autoSilenceMs)
        assertEquals(40000, runtime.autoMaxDurationMs)
    }
}