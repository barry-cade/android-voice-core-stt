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

    // ── Valid configs ─────────────────────────────────────────────────────

    @Test
    fun validManualStopConfig_returnsNull() {
        val result = SttRunConfigValidator.validate(validManualStopConfig())
        assertNull("Valid MANUAL stop config must return null", result)
    }

    @Test
    fun validAutoSilenceConfig_returnsNull() {
        val result = SttRunConfigValidator.validate(validAutoSilenceConfig())
        assertNull("Valid AUTO_SILENCE config must return null", result)
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

    // ── Engine config string constraints ──────────────────────────────────

    @Test
    fun engineConfig_modelPathBlank_returnsInvalidConfig() {
        val config = validManualStopConfig().copy(
            ttsEngineConfig = TtsEngineConfig(
                modelPath = "   ",
                language = "en",
                debugLoggingEnabled = false
            )
        )
        val result = SttRunConfigValidator.validate(config)
        assertNotNull("Blank modelPath must return INVALID_CONFIG", result)
        assertEquals(SttReturnCode.INVALID_CONFIG, result!!.code)
    }

    @Test
    fun engineConfig_languageBlank_returnsInvalidConfig() {
        val config = validManualStopConfig().copy(
            ttsEngineConfig = TtsEngineConfig(
                modelPath = "/dummy/model.bin",
                language = "",
                debugLoggingEnabled = false
            )
        )
        val result = SttRunConfigValidator.validate(config)
        assertNotNull("Blank language must return INVALID_CONFIG", result)
        assertEquals(SttReturnCode.INVALID_CONFIG, result!!.code)
    }

    // ── VAD config validation ─────────────────────────────────────────────

    @Test
    fun vadConfig_energyThresholdZero_returnsInvalidConfig() {
        val config = validManualStopConfig().copy(
            vadConfig = VadConfig(
                energyThreshold = 0f,
                preRollMs = 100,
                stableChunkSizeMs = 500
            )
        )
        val result = SttRunConfigValidator.validate(config)
        assertNotNull("energyThreshold=0 must return INVALID_CONFIG", result)
        assertEquals(SttReturnCode.INVALID_CONFIG, result!!.code)
    }

    @Test
    fun vadConfig_preRollMsNegative_returnsInvalidConfig() {
        val config = validManualStopConfig().copy(
            vadConfig = VadConfig(
                energyThreshold = 0.03f,
                preRollMs = -1,
                stableChunkSizeMs = 500
            )
        )
        val result = SttRunConfigValidator.validate(config)
        assertNotNull("Negative preRollMs must return INVALID_CONFIG", result)
        assertEquals(SttReturnCode.INVALID_CONFIG, result!!.code)
    }

    @Test
    fun vadConfig_stableChunkSizeMsNegative_returnsInvalidConfig() {
        val config = validManualStopConfig().copy(
            vadConfig = VadConfig(
                energyThreshold = 0.03f,
                preRollMs = 100,
                stableChunkSizeMs = -1
            )
        )
        val result = SttRunConfigValidator.validate(config)
        assertNotNull("Negative stableChunkSizeMs must return INVALID_CONFIG", result)
        assertEquals(SttReturnCode.INVALID_CONFIG, result!!.code)
    }

    // ── Start strategy validation ─────────────────────────────────────────

    @Test
    fun startStrategy_unknownType_returnsInvalidConfig() {
        val config = validManualStopConfig().copy(
            startStrategy = StartStrategyConfig(type = "INVALID")
        )
        val result = SttRunConfigValidator.validate(config)
        assertNotNull("Unknown startStrategy type must return INVALID_CONFIG", result)
        assertEquals(SttReturnCode.INVALID_CONFIG, result!!.code)
    }

    // ── Stop strategy validation ──────────────────────────────────────────

    @Test
    fun stopStrategy_unknownType_returnsInvalidConfig() {
        val config = validManualStopConfig().copy(
            stopStrategy = StopStrategyConfig(type = "INVALID")
        )
        val result = SttRunConfigValidator.validate(config)
        assertNotNull("Unknown stopStrategy type must return INVALID_CONFIG", result)
        assertEquals(SttReturnCode.INVALID_CONFIG, result!!.code)
    }

    @Test
    fun autoSilence_missingSilenceMs_returnsInvalidConfig() {
        val config = validManualStopConfig().copy(
            stopStrategy = StopStrategyConfig(type = "AUTO_SILENCE", silenceMs = null, maxDurationMs = 30000)
        )
        val result = SttRunConfigValidator.validate(config)
        assertNotNull("AUTO_SILENCE with null silenceMs must return INVALID_CONFIG", result)
        assertEquals(SttReturnCode.INVALID_CONFIG, result!!.code)
    }

    @Test
    fun autoSilence_silenceMsZero_returnsInvalidConfig() {
        val config = validManualStopConfig().copy(
            stopStrategy = StopStrategyConfig(type = "AUTO_SILENCE", silenceMs = 0, maxDurationMs = 30000)
        )
        val result = SttRunConfigValidator.validate(config)
        assertNotNull("AUTO_SILENCE with silenceMs=0 must return INVALID_CONFIG", result)
        assertEquals(SttReturnCode.INVALID_CONFIG, result!!.code)
    }

    @Test
    fun autoSilence_maxDurationMsNull_returnsInvalidConfig() {
        val config = validManualStopConfig().copy(
            stopStrategy = StopStrategyConfig(type = "AUTO_SILENCE", silenceMs = 1200, maxDurationMs = null)
        )
        val result = SttRunConfigValidator.validate(config)
        assertNotNull("AUTO_SILENCE with null maxDurationMs must return INVALID_CONFIG", result)
        assertEquals(SttReturnCode.INVALID_CONFIG, result!!.code)
    }

    @Test
    fun autoSilence_maxDurationMsZero_returnsInvalidConfig() {
        val config = validManualStopConfig().copy(
            stopStrategy = StopStrategyConfig(type = "AUTO_SILENCE", silenceMs = 1200, maxDurationMs = 0)
        )
        val result = SttRunConfigValidator.validate(config)
        assertNotNull("AUTO_SILENCE with maxDurationMs=0 must return INVALID_CONFIG", result)
        assertEquals(SttReturnCode.INVALID_CONFIG, result!!.code)
    }

    // ── DrainMode validation (enum is type-safe) ──────────────────────────

    @Test
    fun drainMode_fromHead_isValid() {
        val config = validManualStopConfig().copy(drainMode = DrainMode.DRAIN_FROM_HEAD)
        val result = SttRunConfigValidator.validate(config)
        assertNull("Valid config with DRAIN_FROM_HEAD must return null", result)
    }

    @Test
    fun drainMode_fromNextFrame_isValid() {
        val config = validManualStopConfig().copy(drainMode = DrainMode.DRAIN_FROM_NEXT_FRAME)
        val result = SttRunConfigValidator.validate(config)
        assertNull("Valid config with DRAIN_FROM_NEXT_FRAME must return null", result)
    }
}
