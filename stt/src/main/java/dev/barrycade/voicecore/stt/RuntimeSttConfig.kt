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
 * @property sessionTimeoutMs Safety timeout for session duration (ms). 0 = no timeout.
 *         Checked in [SpeechToText.transcribe] before the stop strategy gate.
 * @property warmupEnabled Whether Whisper warm-up is enabled.
 * @property warmupDurationMs Duration of warm-up inference (ms).
 * @property highPassCutoffHz High-pass filter cutoff frequency in Hz.
 *        0 = disabled. Passed through from [SttConfig].
 * @property zcrEnabled When true, ZCR validation is enabled.
 *        Passed through from [SttConfig].
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

    // ── Session safety timeout (0 = no timeout) ──────────────────────────
    val sessionTimeoutMs: Int = 0,

    // ── Warm-up fields ───────────────────────────────────────────────────
    val warmupEnabled: Boolean = false,
    val warmupDurationMs: Int = 0,

    // ── Noise resilience fields ──────────────────────────────────────────
    val highPassCutoffHz: Int = 0,
    val zcrEnabled: Boolean = false
) {
    companion object {
        /**
         * Build a [RuntimeSttConfig] from a [SttConfig].
         *
         * Converts the sealed [StartTrigger] and [StopTrigger] into concrete
         * [StartStrategy] and [StopStrategy] instances.
         */
        fun from(sttCfg: SttConfig): RuntimeSttConfig {
            val startStrategy: StartStrategy = createStartStrategy(sttCfg.startTrigger)
            val stopStrategy: StopStrategy = createStopStrategy(sttCfg.stopTrigger)
            val autoSilenceMs = resolveAutoSilenceMs(sttCfg.stopTrigger)
            val autoMaxDurationMs = resolveAutoMaxDurationMs(sttCfg.stopTrigger)

            return RuntimeSttConfig(
                energyThreshold = sttCfg.energyThreshold,
                preRollMs = sttCfg.preRollMs,
                stableChunkSizeMs = sttCfg.stableChunkSizeMs,
                debugLoggingEnabled = sttCfg.debugLoggingEnabled,
                startStrategy = startStrategy,
                stopStrategy = stopStrategy,
                autoSilenceMs = autoSilenceMs,
                autoMaxDurationMs = autoMaxDurationMs,
                sessionTimeoutMs = sttCfg.sessionTimeoutMs,
                warmupEnabled = sttCfg.warmupEnabled,
                warmupDurationMs = sttCfg.warmupDurationMs,
                highPassCutoffHz = sttCfg.highPassCutoffHz,
                zcrEnabled = sttCfg.zcrEnabled
            )
        }

        private fun createStartStrategy(trigger: StartTrigger): StartStrategy {
            return when (trigger) {
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
        }

        private fun createStopStrategy(trigger: StopTrigger): StopStrategy {
            return when (trigger) {
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
        }

        private fun resolveAutoSilenceMs(trigger: StopTrigger): Int {
            return if (trigger is StopTrigger.AutoSilence) trigger.silenceMs else 1200
        }

        private fun resolveAutoMaxDurationMs(trigger: StopTrigger): Int {
            return when (trigger) {
                is StopTrigger.AutoSilence -> trigger.maxDurationMs
                is StopTrigger.Duration -> trigger.maxDurationMs
                is StopTrigger.Manual -> 30000
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

    require(highPassCutoffHz in 0..2000) {
        "highPassCutoffHz=$highPassCutoffHz must be in [0, 2000] Hz"
    }
}

