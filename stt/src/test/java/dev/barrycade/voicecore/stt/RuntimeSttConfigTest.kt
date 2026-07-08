package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Tests for [RuntimeSttConfig] validation.
 *
 * Every [require] assertion in [RuntimeSttConfig.validate] must be
 * tested for both acceptance and rejection.
 *
 * Deprecated global timing fields (silencePaddingMs, maxUtteranceLengthMs,
 * motionMode) have been removed — all timing is now mode-specific.
 */
class RuntimeSttConfigTest {

    @Test
    fun validate_defaultConfig_succeeds() {
        val config = RuntimeSttConfig()
        config.validate()
    }

    @Test
    fun validate_energyThresholdLowBound_succeeds() {
        val config = RuntimeSttConfig(shared = SharedSttConfig(energyThreshold = 0.0001f))
        config.validate()
    }

    @Test
    fun validate_energyThresholdHighBound_succeeds() {
        val config = RuntimeSttConfig(shared = SharedSttConfig(energyThreshold = 1f))
        config.validate()
    }

    @Test
    fun validate_energyThresholdBelowMinimum_throws() {
        val config = RuntimeSttConfig(shared = SharedSttConfig(energyThreshold = 0f))
        assertThrows(IllegalArgumentException::class.java) { config.validate() }
    }

    @Test
    fun validate_energyThresholdAboveMaximum_throws() {
        val config = RuntimeSttConfig(shared = SharedSttConfig(energyThreshold = 1.1f))
        assertThrows(IllegalArgumentException::class.java) { config.validate() }
    }

    @Test
    fun validate_preRollMsLowBound_succeeds() {
        val config = RuntimeSttConfig(shared = SharedSttConfig(preRollMs = 0))
        config.validate()
    }

    @Test
    fun validate_preRollMsHighBound_succeeds() {
        val config = RuntimeSttConfig(shared = SharedSttConfig(preRollMs = 2000))
        config.validate()
    }

    @Test
    fun validate_preRollMsAboveMaximum_throws() {
        val config = RuntimeSttConfig(shared = SharedSttConfig(preRollMs = 2001))
        assertThrows(IllegalArgumentException::class.java) { config.validate() }
    }

    @Test
    fun validate_stableChunkSizeMsLowBound_succeeds() {
        val config = RuntimeSttConfig(shared = SharedSttConfig(stableChunkSizeMs = 50))
        config.validate()
    }

    @Test
    fun validate_stableChunkSizeMsAboveMaximum_throws() {
        val config = RuntimeSttConfig(shared = SharedSttConfig(stableChunkSizeMs = 2001))
        assertThrows(IllegalArgumentException::class.java) { config.validate() }
    }

    // ── ManualManual validation ───────────────────────────────────────────

    @Test
    fun validate_manualManualMaxDurationMsLowBound_succeeds() {
        val config = RuntimeSttConfig(
            manualManual = ManualManualConfig(maxDurationMs = 1000)
        )
        config.validate()
    }

    @Test
    fun validate_manualManualMaxDurationMsHighBound_succeeds() {
        val config = RuntimeSttConfig(
            manualManual = ManualManualConfig(maxDurationMs = 60000)
        )
        config.validate()
    }

    @Test
    fun validate_manualManualMaxDurationMsBelowMinimum_throws() {
        val config = RuntimeSttConfig(
            manualManual = ManualManualConfig(maxDurationMs = 999)
        )
        assertThrows(IllegalArgumentException::class.java) { config.validate() }
    }

    @Test
    fun validate_manualManualMaxDurationMsAboveMaximum_throws() {
        val config = RuntimeSttConfig(
            manualManual = ManualManualConfig(maxDurationMs = 60001)
        )
        assertThrows(IllegalArgumentException::class.java) { config.validate() }
    }

    @Test
    fun validate_manualManualAbnormalSilenceMsLowBound_succeeds() {
        val config = RuntimeSttConfig(
            manualManual = ManualManualConfig(abnormalSilenceMs = 50)
        )
        config.validate()
    }

    @Test
    fun validate_manualManualAbnormalSilenceMsHighBound_succeeds() {
        val config = RuntimeSttConfig(
            manualManual = ManualManualConfig(abnormalSilenceMs = 30000)
        )
        config.validate()
    }

    @Test
    fun validate_manualManualAbnormalSilenceMsBelowMinimum_throws() {
        val config = RuntimeSttConfig(
            manualManual = ManualManualConfig(abnormalSilenceMs = 49)
        )
        assertThrows(IllegalArgumentException::class.java) { config.validate() }
    }

    @Test
    fun validate_manualManualAbnormalSilenceMsAboveMaximum_throws() {
        val config = RuntimeSttConfig(
            manualManual = ManualManualConfig(abnormalSilenceMs = 30001)
        )
        assertThrows(IllegalArgumentException::class.java) { config.validate() }
    }

    // ── ManualAuto validation ─────────────────────────────────────────────

    @Test
    fun validate_manualAutoMaxDurationMsLowBound_succeeds() {
        val config = RuntimeSttConfig(
            manualAuto = ManualAutoConfig(maxDurationMs = 1000)
        )
        config.validate()
    }

    @Test
    fun validate_manualAutoMaxDurationMsHighBound_succeeds() {
        val config = RuntimeSttConfig(
            manualAuto = ManualAutoConfig(maxDurationMs = 60000)
        )
        config.validate()
    }

    @Test
    fun validate_manualAutoMaxDurationMsBelowMinimum_throws() {
        val config = RuntimeSttConfig(
            manualAuto = ManualAutoConfig(maxDurationMs = 999)
        )
        assertThrows(IllegalArgumentException::class.java) { config.validate() }
    }

    @Test
    fun validate_manualAutoMaxDurationMsAboveMaximum_throws() {
        val config = RuntimeSttConfig(
            manualAuto = ManualAutoConfig(maxDurationMs = 60001)
        )
        assertThrows(IllegalArgumentException::class.java) { config.validate() }
    }

    @Test
    fun validate_manualAutoAutoSilenceMsLowBound_succeeds() {
        val config = RuntimeSttConfig(
            manualAuto = ManualAutoConfig(autoSilenceMs = 50)
        )
        config.validate()
    }

    @Test
    fun validate_manualAutoAutoSilenceMsHighBound_succeeds() {
        val config = RuntimeSttConfig(
            manualAuto = ManualAutoConfig(autoSilenceMs = 10000)
        )
        config.validate()
    }

    @Test
    fun validate_manualAutoAutoSilenceMsBelowMinimum_throws() {
        val config = RuntimeSttConfig(
            manualAuto = ManualAutoConfig(autoSilenceMs = 49)
        )
        assertThrows(IllegalArgumentException::class.java) { config.validate() }
    }

    @Test
    fun validate_manualAutoAutoSilenceMsAboveMaximum_throws() {
        val config = RuntimeSttConfig(
            manualAuto = ManualAutoConfig(autoSilenceMs = 10001)
        )
        assertThrows(IllegalArgumentException::class.java) { config.validate() }
    }
}
