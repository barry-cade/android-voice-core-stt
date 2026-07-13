package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Tests for [RuntimeSttConfig] validation.
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

    // -- from smoke test -----------------------------------------
    @Test
    fun from_sttConfig_populatesCorrectly() {
        val sttCfg = SttConfig(
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
        val runtime = RuntimeSttConfig.from(sttCfg)
        assertEquals(0.03f, runtime.energyThreshold, 0.001f)
        assertEquals(100, runtime.preRollMs)
        assertEquals(500, runtime.stableChunkSizeMs)
        assertEquals(true, runtime.startStrategy is ManualStart)
        assertEquals(true, runtime.stopStrategy is ManualStop)
    }

    @Test
    fun from_sttConfig_autoSilence_populatesCorrectly() {
        val sttCfg = SttConfig(
            modelPath = "/dummy/model.bin",
            language = "en",
            debugLoggingEnabled = false,
            energyThreshold = 0.05f,
            preRollMs = 200,
            stableChunkSizeMs = 600,
            drainMode = DrainMode.DRAIN_FROM_NEXT_FRAME,
            startTrigger = StartTrigger.Manual,
            stopTrigger = StopTrigger.AutoSilence(silenceMs = 1500, maxDurationMs = 40000)
        )
        val runtime = RuntimeSttConfig.from(sttCfg)
        assertEquals(0.05f, runtime.energyThreshold, 0.001f)
        assertEquals(200, runtime.preRollMs)
        assertEquals(600, runtime.stableChunkSizeMs)
        assertEquals(true, runtime.startStrategy is ManualStart)
        assertEquals(true, runtime.stopStrategy is AutoSilenceStop)
        assertEquals(1500, runtime.autoSilenceMs)
        assertEquals(40000, runtime.autoMaxDurationMs)
    }
}
