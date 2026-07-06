package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Tests for [RuntimeSttConfig] validation.
 *
 * Every [require] assertion in [RuntimeSttConfig.validate] must be
 * tested for both acceptance and rejection.
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
    fun validate_silencePaddingMsLowBound_succeeds() {
        val config = RuntimeSttConfig(silencePaddingMs = 50)
        config.validate()
    }

    @Test
    fun validate_silencePaddingMsHighBound_succeeds() {
        val config = RuntimeSttConfig(silencePaddingMs = 5000)
        config.validate()
    }

    @Test
    fun validate_silencePaddingMsBelowMinimum_throws() {
        val config = RuntimeSttConfig(silencePaddingMs = 49)
        assertThrows(IllegalArgumentException::class.java) { config.validate() }
    }

    @Test
    fun validate_silencePaddingMsAboveMaximum_throws() {
        val config = RuntimeSttConfig(silencePaddingMs = 5001)
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
    fun validate_maxUtteranceLengthMsLowBound_succeeds() {
        val config = RuntimeSttConfig(maxUtteranceLengthMs = 1000)
        config.validate()
    }

    @Test
    fun validate_maxUtteranceLengthMsAboveMaximum_throws() {
        val config = RuntimeSttConfig(maxUtteranceLengthMs = 20001)
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
    fun validate_motionModeEnergyThresholdLowBound_succeeds() {
        val config = RuntimeSttConfig(
            motionMode = MotionModeConfig(energyThreshold = 0.0001f, silencePaddingMs = 300)
        )
        config.validate()
    }

    @Test
    fun validate_motionModeEnergyThresholdAboveMaximum_throws() {
        val config = RuntimeSttConfig(
            motionMode = MotionModeConfig(energyThreshold = 1.1f, silencePaddingMs = 300)
        )
        assertThrows(IllegalArgumentException::class.java) { config.validate() }
    }

    @Test
    fun validate_motionModeSilencePaddingMsLowBound_succeeds() {
        val config = RuntimeSttConfig(
            motionMode = MotionModeConfig(energyThreshold = 0.05f, silencePaddingMs = 50)
        )
        config.validate()
    }

    @Test
    fun validate_motionModeSilencePaddingMsAboveMaximum_throws() {
        val config = RuntimeSttConfig(
            motionMode = MotionModeConfig(energyThreshold = 0.05f, silencePaddingMs = 5001)
        )
        assertThrows(IllegalArgumentException::class.java) { config.validate() }
    }
}
