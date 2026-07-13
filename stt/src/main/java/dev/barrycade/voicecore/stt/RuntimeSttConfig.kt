package dev.barrycade.voicecore.stt

/**
 * Internal runtime configuration for the STT pipeline.
 *
 * All fields are flattened into this single data class. Populated from
 * [SttConfig] via [from].
 *
 * Internal only — external callers use [SttConfig].
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
         * Build a [RuntimeSttConfig] from a [SttConfig].
         *
         * Converts the sealed [StartTrigger] and [StopTrigger] into concrete
         * [StartStrategy] and [StopStrategy] instances.
         */
        fun from(sttCfg: SttConfig): RuntimeSttConfig {
            val startStrategy: StartStrategy = when (val trigger = sttCfg.startTrigger) {
                is StartTrigger.Manual -> ManualStart()
                is StartTrigger.VadStart -> VadStart(
                    VadStartConfig(
                        vadStartThreshold = trigger.vadStartThreshold,
                        minSpeechMs = trigger.minSpeechMs
                    )
                )
                is StartTrigger.WakeWordStart -> WakeWordStart(
                    WakeWordConfig(
                        wakeWord = trigger.wakeWord,
                        confidenceThreshold = trigger.confidenceThreshold
                    )
                )
            }

            val stopStrategy: StopStrategy = when (val trigger = sttCfg.stopTrigger) {
                is StopTrigger.Manual -> ManualStop()
                is StopTrigger.AutoSilence -> AutoSilenceStop(
                    AutoSilenceConfig(
                        silenceMs = trigger.silenceMs,
                        maxDurationMs = trigger.maxDurationMs
                    )
                )
                is StopTrigger.Duration -> DurationStop(
                    maxDurationMs = trigger.maxDurationMs
                )
            }

            val autoSilenceMs = when (val trigger = sttCfg.stopTrigger) {
                is StopTrigger.AutoSilence -> trigger.silenceMs
                else -> 1200
            }
            val autoMaxDurationMs = when (val trigger = sttCfg.stopTrigger) {
                is StopTrigger.AutoSilence -> trigger.maxDurationMs
                is StopTrigger.Duration -> trigger.maxDurationMs
                is StopTrigger.Manual -> 30000
            }

            return RuntimeSttConfig(
                energyThreshold = sttCfg.energyThreshold,
                preRollMs = sttCfg.preRollMs,
                stableChunkSizeMs = sttCfg.stableChunkSizeMs,
                debugLoggingEnabled = sttCfg.debugLoggingEnabled,
                startStrategy = startStrategy,
                stopStrategy = stopStrategy,
                autoSilenceMs = autoSilenceMs,
                autoMaxDurationMs = autoMaxDurationMs,
                warmupEnabled = sttCfg.warmupEnabled,
                warmupDurationMs = sttCfg.warmupDurationMs
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

