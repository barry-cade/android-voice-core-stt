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
    fun validate_stableChunkSizeMsAboveMaximum_throws() {
        val config = RuntimeSttConfig(stableChunkSizeMs = 2001)
        assertThrows(IllegalArgumentException::class.java) { config.validate() }
    }

    @Test
    fun validate_manualManualMaxDurationMsLowBound_succeeds() {
        val config = RuntimeSttConfig(manualManualMaxDurationMs = 1000)
        config.validate()
    }

    @Test
    fun validate_manualManualMaxDurationMsHighBound_succeeds() {
        val config = RuntimeSttConfig(manualManualMaxDurationMs = 60000)
        config.validate()
    }

    @Test
    fun validate_manualManualMaxDurationMsBelowMinimum_throws() {
        val config = RuntimeSttConfig(manualManualMaxDurationMs = 999)
        assertThrows(IllegalArgumentException::class.java) { config.validate() }
    }

    @Test
    fun validate_manualManualMaxDurationMsAboveMaximum_throws() {
        val config = RuntimeSttConfig(manualManualMaxDurationMs = 60001)
        assertThrows(IllegalArgumentException::class.java) { config.validate() }
    }

    @Test
    fun validate_manualManualAbnormalSilenceMsLowBound_succeeds() {
        val config = RuntimeSttConfig(manualManualAbnormalSilenceMs = 50)
        config.validate()
    }

    @Test
    fun validate_manualManualAbnormalSilenceMsHighBound_succeeds() {
        val config = RuntimeSttConfig(manualManualAbnormalSilenceMs = 30000)
        config.validate()
    }

    @Test
    fun validate_manualManualAbnormalSilenceMsBelowMinimum_throws() {
        val config = RuntimeSttConfig(manualManualAbnormalSilenceMs = 49)
        assertThrows(IllegalArgumentException::class.java) { config.validate() }
    }

    @Test
    fun validate_manualManualAbnormalSilenceMsAboveMaximum_throws() {
        val config = RuntimeSttConfig(manualManualAbnormalSilenceMs = 30001)
        assertThrows(IllegalArgumentException::class.java) { config.validate() }
    }

    @Test
    fun validate_manualAutoMaxDurationMsLowBound_succeeds() {
        val config = RuntimeSttConfig(manualAutoMaxDurationMs = 1000)
        config.validate()
    }

    @Test
    fun validate_manualAutoMaxDurationMsHighBound_succeeds() {
        val config = RuntimeSttConfig(manualAutoMaxDurationMs = 60000)
        config.validate()
    }

    @Test
    fun validate_manualAutoMaxDurationMsBelowMinimum_throws() {
        val config = RuntimeSttConfig(manualAutoMaxDurationMs = 999)
        assertThrows(IllegalArgumentException::class.java) { config.validate() }
    }

    @Test
    fun validate_manualAutoMaxDurationMsAboveMaximum_throws() {
        val config = RuntimeSttConfig(manualAutoMaxDurationMs = 60001)
        assertThrows(IllegalArgumentException::class.java) { config.validate() }
    }

    @Test
    fun validate_manualAutoAutoSilenceMsLowBound_succeeds() {
        val config = RuntimeSttConfig(manualAutoAutoSilenceMs = 50)
        config.validate()
    }

    @Test
    fun validate_manualAutoAutoSilenceMsHighBound_succeeds() {
        val config = RuntimeSttConfig(manualAutoAutoSilenceMs = 10000)
        config.validate()
    }

    @Test
    fun validate_manualAutoAutoSilenceMsBelowMinimum_throws() {
        val config = RuntimeSttConfig(manualAutoAutoSilenceMs = 49)
        assertThrows(IllegalArgumentException::class.java) { config.validate() }
    }

    @Test
    fun validate_manualAutoAutoSilenceMsAboveMaximum_throws() {
        val config = RuntimeSttConfig(manualAutoAutoSilenceMs = 10001)
        assertThrows(IllegalArgumentException::class.java) { config.validate() }
    }
}