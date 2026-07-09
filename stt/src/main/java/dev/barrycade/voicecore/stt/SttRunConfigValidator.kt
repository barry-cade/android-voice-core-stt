package dev.barrycade.voicecore.stt

/**
 * Deterministic validator for [SttRunConfig].
 *
 * Validates every field according to the rules in §4 of the STT config contract.
 * Returns null if the config is valid, or [SessionResult] with [SttReturnCode.INVALID_CONFIG]
 * if any field fails validation.
 *
 * No inference, no defaults, no human-readable messages.
 */
internal object SttRunConfigValidator {

    /**
     * Validate [config] against all field-level rules.
     *
     * @return null if valid, or `SessionResult(SttReturnCode.INVALID_CONFIG, null)` if invalid.
     */
    fun validate(config: SttRunConfig?): SessionResult? {
        if (config == null) {
            return SessionResult(SttReturnCode.INVALID_CONFIG, null)
        }

        // ── Validate ttsEngineConfig ──────────────────────────────────────
        val engine = config.ttsEngineConfig

        if (engine.modelPath.isBlank()) {
            return SessionResult(SttReturnCode.INVALID_CONFIG, null)
        }

        if (engine.language.isBlank()) {
            return SessionResult(SttReturnCode.INVALID_CONFIG, null)
        }

        if (engine.preRollMs < 0) {
            return SessionResult(SttReturnCode.INVALID_CONFIG, null)
        }

        if (engine.stableChunkSizeMs < 0) {
            return SessionResult(SttReturnCode.INVALID_CONFIG, null)
        }

        // debugLoggingEnabled is Boolean — no range check needed, type is enforced by Kotlin.

        // ── Validate ttsLifeCycleStrategy ─────────────────────────────────
        // Enum type is enforced by Kotlin at compile time, so any value is valid.

        // ── Validate strategySpecific type contract ───────────────────────
        val strategy = config.ttsLifeCycleStrategy
        val specific = config.strategySpecific

        when (strategy) {
            SttLifeCycleStrategy.MANUAL_MANUAL -> {
                if (specific !is ManualManualSpecific) {
                    return SessionResult(SttReturnCode.INVALID_CONFIG, null)
                }
                val mm = specific
                if (mm.energyThreshold <= 0f) {
                    return SessionResult(SttReturnCode.INVALID_CONFIG, null)
                }
                if (mm.maxDurationMs <= 0) {
                    return SessionResult(SttReturnCode.INVALID_CONFIG, null)
                }
                if (mm.abnormalSilenceMs <= 0) {
                    return SessionResult(SttReturnCode.INVALID_CONFIG, null)
                }
            }

            SttLifeCycleStrategy.MANUAL_AUTO -> {
                if (specific !is ManualAutoSpecific) {
                    return SessionResult(SttReturnCode.INVALID_CONFIG, null)
                }
                val ma = specific
                if (ma.energyThreshold <= 0f) {
                    return SessionResult(SttReturnCode.INVALID_CONFIG, null)
                }
                if (ma.maxDurationMs <= 0) {
                    return SessionResult(SttReturnCode.INVALID_CONFIG, null)
                }
                if (ma.autoSilenceMs <= 0) {
                    return SessionResult(SttReturnCode.INVALID_CONFIG, null)
                }
            }
        }

        // All validations passed.
        return null
    }
}
