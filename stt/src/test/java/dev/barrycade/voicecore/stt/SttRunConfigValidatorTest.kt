package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Deterministic tests for [SttRunConfigValidator].
 *
 * Validates every field-level rule defined in §4 of the STT config contract.
 * All tests are pure JVM — no Android dependencies, no audio hardware.
 */
class SttRunConfigValidatorTest {

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

    // ── Valid configs ─────────────────────────────────────────────────────

    @Test
    fun validManualManualConfig_returnsNull() {
        val result = SttRunConfigValidator.validate(validManualManualConfig())
        assertNull("Valid MANUAL_MANUAL config must return null", result)
    }

    @Test
    fun validManualAutoConfig_returnsNull() {
        val result = SttRunConfigValidator.validate(validManualAutoConfig())
        assertNull("Valid MANUAL_AUTO config must return null", result)
    }

    // ── Null config ───────────────────────────────────────────────────────

    @Test
    fun nullConfig_returnsInvalidConfig() {
        val result = SttRunConfigValidator.validate(null)
        assertNotNull("Null config must return INVALID_CONFIG", result)
        assertEquals(
            "Null config must return INVALID_CONFIG",
            SttReturnCode.INVALID_CONFIG,
            result!!.code
        )
    }

    // ── Wrong type for strategySpecific ───────────────────────────────────

    @Test
    fun manualManual_withAutoSpecific_returnsInvalidConfig() {
        val config = SttRunConfig(
            ttsEngineConfig = validManualManualConfig().ttsEngineConfig,
            ttsLifeCycleStrategy = SttLifeCycleStrategy.MANUAL_MANUAL,
            strategySpecific = ManualAutoSpecific(
                energyThreshold = 0.03f,
                maxDurationMs = 30000,
                autoSilenceMs = 1200
            )
        )
        val result = SttRunConfigValidator.validate(config)
        assertNotNull(
            "MANUAL_MANUAL with ManualAutoSpecific must return INVALID_CONFIG",
            result
        )
        assertEquals(SttReturnCode.INVALID_CONFIG, result!!.code)
    }

    @Test
    fun manualAuto_withManualSpecific_returnsInvalidConfig() {
        val config = SttRunConfig(
            ttsEngineConfig = validManualAutoConfig().ttsEngineConfig,
            ttsLifeCycleStrategy = SttLifeCycleStrategy.MANUAL_AUTO,
            strategySpecific = ManualManualSpecific(
                energyThreshold = 0.03f,
                maxDurationMs = 30000,
                abnormalSilenceMs = 5000,
                drainMode = DrainMode.DRAIN_FROM_NEXT_FRAME
            )
        )
        val result = SttRunConfigValidator.validate(config)
        assertNotNull(
            "MANUAL_AUTO with ManualManualSpecific must return INVALID_CONFIG",
            result
        )
        assertEquals(SttReturnCode.INVALID_CONFIG, result!!.code)
    }

    @Test
    fun strategySpecific_withWrongType_returnsInvalidConfig() {
        val config = SttRunConfig(
            ttsEngineConfig = validManualManualConfig().ttsEngineConfig,
            ttsLifeCycleStrategy = SttLifeCycleStrategy.MANUAL_MANUAL,
            strategySpecific = "not a valid type"
        )
        val result = SttRunConfigValidator.validate(config)
        assertNotNull("Wrong type for strategySpecific must return INVALID_CONFIG", result)
        assertEquals(SttReturnCode.INVALID_CONFIG, result!!.code)
    }

    // ── drainMode validation ──────────────────────────────────────────────

    @Test
    fun manualManual_drainModeDefault_isDrainFromNextFrame() {
        val config = validManualManualConfig()
        val specific = config.strategySpecific as ManualManualSpecific
        assertEquals(
            "Default drainMode must be DRAIN_FROM_NEXT_FRAME",
            DrainMode.DRAIN_FROM_NEXT_FRAME,
            specific.drainMode
        )
    }

    @Test
    fun manualManual_drainModeFromHead_isValid() {
        val config = validManualManualConfig().copy(
            strategySpecific = ManualManualSpecific(
                energyThreshold = 0.03f,
                maxDurationMs = 30000,
                abnormalSilenceMs = 5000,
                drainMode = DrainMode.DRAIN_FROM_HEAD
            )
        )
        val result = SttRunConfigValidator.validate(config)
        assertNull("Valid config with DRAIN_FROM_HEAD must return null", result)
    }

    @Test
    fun manualManual_drainModeFromNextFrame_isValid() {
        val config = validManualManualConfig().copy(
            strategySpecific = ManualManualSpecific(
                energyThreshold = 0.03f,
                maxDurationMs = 30000,
                abnormalSilenceMs = 5000,
                drainMode = DrainMode.DRAIN_FROM_NEXT_FRAME
            )
        )
        val result = SttRunConfigValidator.validate(config)
        assertNull("Valid config with DRAIN_FROM_NEXT_FRAME must return null", result)
    }

    // ── Numeric constraints — MANUAL_MANUAL ───────────────────────────────

    @Test
    fun manualManual_energyThresholdZero_returnsInvalidConfig() {
        val config = validManualManualConfig().copy(
            strategySpecific = ManualManualSpecific(
                energyThreshold = 0f,
                maxDurationMs = 30000,
                abnormalSilenceMs = 5000,
                drainMode = DrainMode.DRAIN_FROM_NEXT_FRAME
            )
        )
        val result = SttRunConfigValidator.validate(config)
        assertNotNull("energyThreshold=0 must return INVALID_CONFIG", result)
        assertEquals(SttReturnCode.INVALID_CONFIG, result!!.code)
    }

    @Test
    fun manualManual_energyThresholdNegative_returnsInvalidConfig() {
        val config = validManualManualConfig().copy(
            strategySpecific = ManualManualSpecific(
                energyThreshold = -0.01f,
                maxDurationMs = 30000,
                abnormalSilenceMs = 5000,
                drainMode = DrainMode.DRAIN_FROM_NEXT_FRAME
            )
        )
        val result = SttRunConfigValidator.validate(config)
        assertNotNull("energyThreshold negative must return INVALID_CONFIG", result)
        assertEquals(SttReturnCode.INVALID_CONFIG, result!!.code)
    }

    @Test
    fun manualManual_maxDurationMsZero_returnsInvalidConfig() {
        val config = validManualManualConfig().copy(
            strategySpecific = ManualManualSpecific(
                energyThreshold = 0.03f,
                maxDurationMs = 0,
                abnormalSilenceMs = 5000,
                drainMode = DrainMode.DRAIN_FROM_NEXT_FRAME
            )
        )
        val result = SttRunConfigValidator.validate(config)
        assertNotNull("maxDurationMs=0 must return INVALID_CONFIG", result)
        assertEquals(SttReturnCode.INVALID_CONFIG, result!!.code)
    }

    @Test
    fun manualManual_abnormalSilenceMsZero_returnsInvalidConfig() {
        val config = validManualManualConfig().copy(
            strategySpecific = ManualManualSpecific(
                energyThreshold = 0.03f,
                maxDurationMs = 30000,
                abnormalSilenceMs = 0,
                drainMode = DrainMode.DRAIN_FROM_NEXT_FRAME
            )
        )
        val result = SttRunConfigValidator.validate(config)
        assertNotNull("abnormalSilenceMs=0 must return INVALID_CONFIG", result)
        assertEquals(SttReturnCode.INVALID_CONFIG, result!!.code)
    }

    // ── Numeric constraints — MANUAL_AUTO ─────────────────────────────────

    @Test
    fun manualAuto_energyThresholdZero_returnsInvalidConfig() {
        val config = validManualAutoConfig().copy(
            strategySpecific = ManualAutoSpecific(
                energyThreshold = 0f,
                maxDurationMs = 30000,
                autoSilenceMs = 1200
            )
        )
        val result = SttRunConfigValidator.validate(config)
        assertNotNull("energyThreshold=0 must return INVALID_CONFIG", result)
        assertEquals(SttReturnCode.INVALID_CONFIG, result!!.code)
    }

    @Test
    fun manualAuto_maxDurationMsNegative_returnsInvalidConfig() {
        val config = validManualAutoConfig().copy(
            strategySpecific = ManualAutoSpecific(
                energyThreshold = 0.03f,
                maxDurationMs = -1,
                autoSilenceMs = 1200
            )
        )
        val result = SttRunConfigValidator.validate(config)
        assertNotNull("maxDurationMs negative must return INVALID_CONFIG", result)
        assertEquals(SttReturnCode.INVALID_CONFIG, result!!.code)
    }

    @Test
    fun manualAuto_autoSilenceMsZero_returnsInvalidConfig() {
        val config = validManualAutoConfig().copy(
            strategySpecific = ManualAutoSpecific(
                energyThreshold = 0.03f,
                maxDurationMs = 30000,
                autoSilenceMs = 0
            )
        )
        val result = SttRunConfigValidator.validate(config)
        assertNotNull("autoSilenceMs=0 must return INVALID_CONFIG", result)
        assertEquals(SttReturnCode.INVALID_CONFIG, result!!.code)
    }

    // ── Engine config string constraints ──────────────────────────────────

    @Test
    fun engineConfig_modelPathBlank_returnsInvalidConfig() {
        val config = validManualManualConfig().copy(
            ttsEngineConfig = TtsEngineConfig(
                modelPath = "   ",
                language = "en",
                preRollMs = 100,
                stableChunkSizeMs = 500,
                debugLoggingEnabled = false
            )
        )
        val result = SttRunConfigValidator.validate(config)
        assertNotNull("Blank modelPath must return INVALID_CONFIG", result)
        assertEquals(SttReturnCode.INVALID_CONFIG, result!!.code)
    }

    @Test
    fun engineConfig_languageBlank_returnsInvalidConfig() {
        val config = validManualManualConfig().copy(
            ttsEngineConfig = TtsEngineConfig(
                modelPath = "/dummy/model.bin",
                language = "",
                preRollMs = 100,
                stableChunkSizeMs = 500,
                debugLoggingEnabled = false
            )
        )
        val result = SttRunConfigValidator.validate(config)
        assertNotNull("Blank language must return INVALID_CONFIG", result)
        assertEquals(SttReturnCode.INVALID_CONFIG, result!!.code)
    }

    // ── Engine config numeric constraints ─────────────────────────────────

    @Test
    fun engineConfig_preRollMsNegative_returnsInvalidConfig() {
        val config = validManualManualConfig().copy(
            ttsEngineConfig = TtsEngineConfig(
                modelPath = "/dummy/model.bin",
                language = "en",
                preRollMs = -1,
                stableChunkSizeMs = 500,
                debugLoggingEnabled = false
            )
        )
        val result = SttRunConfigValidator.validate(config)
        assertNotNull("Negative preRollMs must return INVALID_CONFIG", result)
        assertEquals(SttReturnCode.INVALID_CONFIG, result!!.code)
    }

    @Test
    fun engineConfig_stableChunkSizeMsNegative_returnsInvalidConfig() {
        val config = validManualManualConfig().copy(
            ttsEngineConfig = TtsEngineConfig(
                modelPath = "/dummy/model.bin",
                language = "en",
                preRollMs = 100,
                stableChunkSizeMs = -1,
                debugLoggingEnabled = false
            )
        )
        val result = SttRunConfigValidator.validate(config)
        assertNotNull("Negative stableChunkSizeMs must return INVALID_CONFIG", result)
        assertEquals(SttReturnCode.INVALID_CONFIG, result!!.code)
    }
}
