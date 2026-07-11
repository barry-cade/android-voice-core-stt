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
    // ── VAD fields ───────────────────────────────────────────────────────
    val energyThreshold: Float = 0.03f,
    val preRollMs: Int = 100,
    val stableChunkSizeMs: Int = 500,
    val debugLoggingEnabled: Boolean = false,

    // ── Stop-strategy fields ─────────────────────────────────────────────
    val manualStopMode: Boolean = true,
    val autoSilenceMs: Int = 1200,
    val autoMaxDurationMs: Int = 30000
) {
    companion object {
        /**
         * Build a [RuntimeSttConfig] from a [SttRunConfig].
         */
        fun fromSttRunConfig(runCfg: SttRunConfig): RuntimeSttConfig {
            val vad = runCfg.vadConfig
            val stop = runCfg.stopStrategy

            val manualStopMode = stop.type == "MANUAL"
            val autoSilenceMs = if (!manualStopMode) stop.silenceMs ?: 1200 else 1200
            val autoMaxDurationMs = if (!manualStopMode) stop.maxDurationMs ?: 30000 else 30000

            return RuntimeSttConfig(
                energyThreshold = vad.energyThreshold,
                preRollMs = vad.preRollMs,
                stableChunkSizeMs = vad.stableChunkSizeMs,
                debugLoggingEnabled = runCfg.ttsEngineConfig.debugLoggingEnabled,
                manualStopMode = manualStopMode,
                autoSilenceMs = autoSilenceMs,
                autoMaxDurationMs = autoMaxDurationMs
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
}

