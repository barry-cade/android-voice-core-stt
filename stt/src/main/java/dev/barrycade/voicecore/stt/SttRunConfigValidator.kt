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

        // ── Validate vadConfig ────────────────────────────────────────────
        val vad = config.vadConfig

        if (vad.energyThreshold <= 0f) {
            return SessionResult(SttReturnCode.INVALID_CONFIG, null)
        }

        if (vad.preRollMs < 0) {
            return SessionResult(SttReturnCode.INVALID_CONFIG, null)
        }

        if (vad.stableChunkSizeMs < 0) {
            return SessionResult(SttReturnCode.INVALID_CONFIG, null)
        }

        // ── Validate drainMode ────────────────────────────────────────────
        // Enum type enforced by Kotlin, so any DrainMode value is valid.

        // ── Validate startStrategy ────────────────────────────────────────
        val start = config.startStrategy

        when (start.type) {
            "MANUAL" -> {
                if (start.vadStartThreshold != null || start.minSpeechMs != null ||
                    start.wakeWord != null || start.confidenceThreshold != null
                ) {
                    return SessionResult(SttReturnCode.INVALID_CONFIG, null)
                }
            }
            "VAD_START" -> {
                if (start.vadStartThreshold == null || start.vadStartThreshold <= 0f) {
                    return SessionResult(SttReturnCode.INVALID_CONFIG, null)
                }
                if (start.minSpeechMs == null || start.minSpeechMs <= 0) {
                    return SessionResult(SttReturnCode.INVALID_CONFIG, null)
                }
                if (start.wakeWord != null || start.confidenceThreshold != null) {
                    return SessionResult(SttReturnCode.INVALID_CONFIG, null)
                }
            }
            "WAKEWORD" -> {
                if (start.wakeWord == null || start.wakeWord.isBlank()) {
                    return SessionResult(SttReturnCode.INVALID_CONFIG, null)
                }
                if (start.confidenceThreshold == null || start.confidenceThreshold <= 0f) {
                    return SessionResult(SttReturnCode.INVALID_CONFIG, null)
                }
                if (start.vadStartThreshold != null || start.minSpeechMs != null) {
                    return SessionResult(SttReturnCode.INVALID_CONFIG, null)
                }
            }
            else -> return SessionResult(SttReturnCode.INVALID_CONFIG, null)
        }

        // ── Validate stopStrategy ─────────────────────────────────────────
        val stop = config.stopStrategy

        when (stop.type) {
            "MANUAL" -> {
                if (stop.silenceMs != null || stop.maxDurationMs != null) {
                    return SessionResult(SttReturnCode.INVALID_CONFIG, null)
                }
            }
            "AUTO_SILENCE" -> {
                if (stop.silenceMs == null || stop.silenceMs <= 0) {
                    return SessionResult(SttReturnCode.INVALID_CONFIG, null)
                }
                if (stop.maxDurationMs == null || stop.maxDurationMs <= 0) {
                    return SessionResult(SttReturnCode.INVALID_CONFIG, null)
                }
            }
            "DURATION" -> {
                if (stop.silenceMs != null) {
                    return SessionResult(SttReturnCode.INVALID_CONFIG, null)
                }
                if (stop.maxDurationMs == null || stop.maxDurationMs <= 0) {
                    return SessionResult(SttReturnCode.INVALID_CONFIG, null)
                }
            }
            else -> return SessionResult(SttReturnCode.INVALID_CONFIG, null)
        }

        // All validations passed.
        return null
    }
}

