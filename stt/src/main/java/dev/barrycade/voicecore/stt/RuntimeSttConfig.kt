@file:Suppress("DEPRECATION")
package dev.barrycade.voicecore.stt

/**
 * Internal runtime configuration for the STT pipeline.
 *
 * All fields are flattened into this single data class. Populated from
 * [SttRunConfig] via [fromSttRunConfig].
 *
 * Internal only — external callers use [SttRunConfig].
 *
 * @property energyThreshold VAD energy threshold for speech detection.
 * @property preRollMs Pre-roll window before speech detection (ms).
 * @property stableChunkSizeMs Chunk size for stable frame processing (ms).
 *         Mapped to UtteranceAccumulator's utteranceSilenceTimeoutMs.
 * @property debugLoggingEnabled Whether debug logging is enabled.
 * @property startStrategy Start strategy instance.
 * @property stopStrategy Stop strategy instance.
 * @property autoSilenceMs Silence threshold used by StopStrategy for auto-silence mode (ms).
 * @property autoMaxDurationMs Max utterance duration (ms). Mapped to UtteranceAccumulator's
 *         utteranceMaxDurationMs. Also used by StopStrategy for auto-silence and duration modes.
 * @property warmupEnabled Whether Whisper warm-up is enabled.
 * @property warmupDurationMs Duration of warm-up inference (ms).
 */
internal data class RuntimeSttConfig(
    // ── VAD fields ───────────────────────────────────────────────────────
    val energyThreshold: Float = 0.03f,
    val preRollMs: Int = 100,
    val stableChunkSizeMs: Int = 500,
    val debugLoggingEnabled: Boolean = false,

    // ── Strategy instances ───────────────────────────────────────────────
    val startStrategy: StartStrategy = ManualStart(),
    val stopStrategy: StopStrategy = ManualStop(),

    // ── Utterance-accumulator fields ─────────────────────────────────────
    val autoSilenceMs: Int = 1200,
    val autoMaxDurationMs: Int = 30000,

    // ── Warm-up fields ───────────────────────────────────────────────────
    val warmupEnabled: Boolean = false,
    val warmupDurationMs: Int = 0
) {
    companion object {
        /**
         * Build a [RuntimeSttConfig] from a [SttRunConfig].
         *
         * Converts [SttRunConfig.startStrategy] and [SttRunConfig.stopStrategy]
         * into concrete [StartStrategy] and [StopStrategy] instances.
         */
        fun fromSttRunConfig(runCfg: SttRunConfig): RuntimeSttConfig {
            val vad = runCfg.vadConfig
            val startCfg = runCfg.startStrategy
            val stopCfg = runCfg.stopStrategy

            val startStrategy: StartStrategy = buildStartStrategy(startCfg)
            val stopStrategy: StopStrategy = buildStopStrategy(stopCfg)
            val autoSilenceMs = stopCfg.silenceMs ?: 1200
            val autoMaxDurationMs = stopCfg.maxDurationMs ?: 30000

            return RuntimeSttConfig(
                energyThreshold = vad.energyThreshold,
                preRollMs = vad.preRollMs,
                stableChunkSizeMs = vad.stableChunkSizeMs,
                debugLoggingEnabled = runCfg.ttsEngineConfig.debugLoggingEnabled,
                startStrategy = startStrategy,
                stopStrategy = stopStrategy,
                autoSilenceMs = autoSilenceMs,
                autoMaxDurationMs = autoMaxDurationMs,
                warmupEnabled = runCfg.warmupEnabled,
                warmupDurationMs = runCfg.warmupDurationMs
            )
        }

        private fun buildStartStrategy(cfg: StartStrategyConfig): StartStrategy {
            return when (cfg.type.uppercase()) {
                "MANUAL" -> ManualStart()
                "VAD_START" -> VadStart(
                    VadStartConfig(
                        vadStartThreshold = cfg.vadStartThreshold
                            ?: throw IllegalArgumentException("vadStartThreshold required for VAD_START"),
                        minSpeechMs = cfg.minSpeechMs
                            ?: throw IllegalArgumentException("minSpeechMs required for VAD_START")
                    )
                )
                "WAKEWORD" -> {
                    val wakeWord = cfg.wakeWord
                        ?: throw IllegalArgumentException("wakeWord required for WAKEWORD")
                    val threshold = cfg.confidenceThreshold
                        ?: throw IllegalArgumentException("confidenceThreshold required for WAKEWORD")
                    WakeWordStart(
                        WakeWordConfig(
                            wakeWord = wakeWord,
                            confidenceThreshold = threshold
                        )
                    )
                }
                else -> throw IllegalArgumentException("Unknown start strategy type: ${cfg.type}")
            }
        }

        private fun buildStopStrategy(cfg: StopStrategyConfig): StopStrategy {
            return when (cfg.type.uppercase()) {
                "MANUAL" -> ManualStop()
                "AUTO_SILENCE" -> AutoSilenceStop(
                    AutoSilenceConfig(
                        silenceMs = cfg.silenceMs
                            ?: throw IllegalArgumentException("silenceMs required for AUTO_SILENCE"),
                        maxDurationMs = cfg.maxDurationMs
                            ?: throw IllegalArgumentException("maxDurationMs required for AUTO_SILENCE")
                    )
                )
                "DURATION" -> DurationStop(
                    maxDurationMs = cfg.maxDurationMs
                        ?: throw IllegalArgumentException("maxDurationMs required for DURATION")
                )
                else -> throw IllegalArgumentException("Unknown stop strategy type: ${cfg.type}")
            }
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

