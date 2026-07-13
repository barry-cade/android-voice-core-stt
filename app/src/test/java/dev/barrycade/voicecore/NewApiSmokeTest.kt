package dev.barrycade.voicecore

import dev.barrycade.voicecore.stt.DrainMode
import dev.barrycade.voicecore.stt.SttConfig
import dev.barrycade.voicecore.stt.StopTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smoke test for [SttConfig] construction and field defaults.
 *
 * Tests that:
 * - [SttConfig] can be constructed with valid values
 * - Field accessors return expected values
 * - [DrainMode] and sealed strategy types wire correctly
 *
 * These tests do NOT require Android SDK (no AudioRecord etc).
 */
class NewApiSmokeTest {

    private fun validManualStopConfig(): SttConfig {
        return SttConfig(
            modelPath = "/dummy/model.bin",
            language = "en",
            debugLoggingEnabled = false,
            energyThreshold = 0.03f,
            preRollMs = 100,
            stableChunkSizeMs = 500,
            drainMode = DrainMode.DRAIN_FROM_NEXT_FRAME,
            startTrigger = dev.barrycade.voicecore.stt.StartTrigger.Manual,
            stopTrigger = StopTrigger.Manual
        )
    }

    private fun validAutoSilenceConfig(): SttConfig {
        return SttConfig(
            modelPath = "/dummy/model.bin",
            language = "en",
            debugLoggingEnabled = false,
            energyThreshold = 0.03f,
            preRollMs = 100,
            stableChunkSizeMs = 500,
            drainMode = DrainMode.DRAIN_FROM_NEXT_FRAME,
            startTrigger = dev.barrycade.voicecore.stt.StartTrigger.Manual,
            stopTrigger = StopTrigger.AutoSilence(silenceMs = 1200, maxDurationMs = 30000)
        )
    }

    @Test
    fun runConfig_manualStop_constructsSuccessfully() {
        val config = validManualStopConfig()
        assertNotNull(config)
        assertTrue(config.stopTrigger is StopTrigger.Manual)
    }

    @Test
    fun runConfig_autoSilence_constructsSuccessfully() {
        val config = validAutoSilenceConfig()
        assertNotNull(config)
        assertTrue(config.stopTrigger is StopTrigger.AutoSilence)
    }

    @Test
    fun runConfig_manualStop_hasExpectedVadConfig() {
        val config = validManualStopConfig()
        assertEquals(0.03f, config.energyThreshold, 0.001f)
        assertEquals(100, config.preRollMs)
        assertEquals(500, config.stableChunkSizeMs)
    }
}
