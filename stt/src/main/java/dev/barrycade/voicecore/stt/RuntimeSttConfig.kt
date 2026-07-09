package dev.barrycade.voicecore.stt

/**
 * Internal runtime configuration for the STT pipeline.
 *
 * All fields are flattened into this single data class. Populated from
 * [SttRunConfig] via [fromSttRunConfig].
 *
 * Internal only — external callers use [SttRunConfig].
 */
internal data class RuntimeSttConfig(
    // ── Shared engine fields ──────────────────────────────────────────────
    val energyThreshold: Float = 0.03f,
    val preRollMs: Int = 100,
    val stableChunkSizeMs: Int = 500,
    val debugLoggingEnabled: Boolean = false,

    // ── Manual/manual-specific fields ────────────────────────────────────
    val manualManualMaxDurationMs: Int = 30000,
    val manualManualAbnormalSilenceMs: Int = 5000,

    // ── Manual/auto-specific fields ──────────────────────────────────────
    val manualAutoMaxDurationMs: Int = 30000,
    val manualAutoAutoSilenceMs: Int = 1200
) {
    companion object {
        /**
         * Build a [RuntimeSttConfig] from a [SttRunConfig].
         */
        fun fromSttRunConfig(runCfg: SttRunConfig): RuntimeSttConfig {
            val engine = runCfg.ttsEngineConfig
            val specific = runCfg.strategySpecific

            val energyThreshold = when (specific) {
                is ManualManualSpecific -> specific.energyThreshold
                is ManualAutoSpecific -> specific.energyThreshold
                else -> 0.03f
            }

            val manualManualMaxDurationMs: Int
            val manualManualAbnormalSilenceMs: Int
            val manualAutoMaxDurationMs: Int
            val manualAutoAutoSilenceMs: Int

            when (specific) {
                is ManualManualSpecific -> {
                    manualManualMaxDurationMs = specific.maxDurationMs
                    manualManualAbnormalSilenceMs = specific.abnormalSilenceMs
                    manualAutoMaxDurationMs = 30000
                    manualAutoAutoSilenceMs = 1200
                }
                is ManualAutoSpecific -> {
                    manualManualMaxDurationMs = 30000
                    manualManualAbnormalSilenceMs = 5000
                    manualAutoMaxDurationMs = specific.maxDurationMs
                    manualAutoAutoSilenceMs = specific.autoSilenceMs
                }
                else -> {
                    manualManualMaxDurationMs = 30000
                    manualManualAbnormalSilenceMs = 5000
                    manualAutoMaxDurationMs = 30000
                    manualAutoAutoSilenceMs = 1200
                }
            }

            return RuntimeSttConfig(
                energyThreshold = energyThreshold,
                preRollMs = engine.preRollMs,
                stableChunkSizeMs = engine.stableChunkSizeMs,
                debugLoggingEnabled = engine.debugLoggingEnabled,
                manualManualMaxDurationMs = manualManualMaxDurationMs,
                manualManualAbnormalSilenceMs = manualManualAbnormalSilenceMs,
                manualAutoMaxDurationMs = manualAutoMaxDurationMs,
                manualAutoAutoSilenceMs = manualAutoAutoSilenceMs
            )
        }
    }
}
internal fun RuntimeSttConfig.validate() {
    require(energyThreshold in 0.0001f..1f) {
        "energyThreshold=$energyThreshold must be in [0.0001, 1]"
    }

    require(preRollMs in 0..2000) {
        "preRollMs=$preRollMs must be in [0, 2000] ms"
    }

    require(stableChunkSizeMs in 50..2000) {
        "stableChunkSizeMs=$stableChunkSizeMs must be in [50, 2000] ms"
    }

    require(manualManualMaxDurationMs in 1000..60000) {
        "manualManualMaxDurationMs=$manualManualMaxDurationMs must be in [1000, 60000] ms"
    }

    require(manualManualAbnormalSilenceMs in 50..30000) {
        "manualManualAbnormalSilenceMs=$manualManualAbnormalSilenceMs must be in [50, 30000] ms"
    }

    require(manualAutoMaxDurationMs in 1000..60000) {
        "manualAutoMaxDurationMs=$manualAutoMaxDurationMs must be in [1000, 60000] ms"
    }

    require(manualAutoAutoSilenceMs in 50..10000) {
        "manualAutoAutoSilenceMs=$manualAutoAutoSilenceMs must be in [50, 10000] ms"
    }
}

