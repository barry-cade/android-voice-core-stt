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
 * @property debugLoggingEnabled Whether debug logging is enabled.
 * @property startStrategy Start strategy instance.
 * @property stopStrategy Stop strategy instance.
 * @property autoSilenceMs Silence threshold for utterance boundary (ms). Maps to
 *         UtteranceAccumulator's utteranceSilenceTimeoutMs. Also used by
 *         AutoSilenceStop strategy for session-level stop decisions.
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
 * @property sttMode Listening mode string. Runtime-safe.
 * @property grammar Optional context/grammar hint string. Runtime-safe.
 * @property partialsEnabled When true, partial results are produced. Runtime-safe.
 * @property autoReturn When true, auto-returns transcript on silence. Runtime-safe.
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
    val zcrEnabled: Boolean = false,

    // ── New public API fields (Phase 1) ────────────────────────────────
    val sttMode: String = "ALWAYS_ON",
    val grammar: String? = null,
    val partialsEnabled: Boolean = false,
    val autoReturn: Boolean = false
) {
    /**
     * Return a copy of this config with the stop strategy overridden.
     *
     * Used by the UI layer to apply a preset and then switch between
     * Manual/Manual and Manual/Auto-Silence modes without modifying
     * any other preset fields.
     *
     * @param autoSilenceEnabled When true, uses AutoSilenceStop; otherwise ManualStop.
     */
    fun withStopStrategyOverride(autoSilenceEnabled: Boolean): RuntimeSttConfig {
        return if (autoSilenceEnabled) {
            copy(
                startStrategy = ManualStart(),
                stopStrategy = AutoSilenceStop(
                    AutoSilenceConfig(
                        silenceMs = autoSilenceMs,
                        maxDurationMs = autoMaxDurationMs
                    )
                )
            )
        } else {
            copy(
                startStrategy = ManualStart(),
                stopStrategy = ManualStop()
            )
        }
    }

    companion object {
        /**
         * Named presets matching the environments documented in STT_MODE_CONFIGS.md.
         *
         * Each preset defines a complete behavioural profile. Only stopStrategy
         * depends on the UI mode radio — use [withStopStrategyOverride] to apply
         * the mode override.
         *
         * All presets use ManualStart() as the base start strategy.
         */
        val presets: Map<String, RuntimeSttConfig> = mapOf(
            "QUIET" to RuntimeSttConfig(
                energyThreshold = 0.0012f,
                preRollMs = 80,
                stableChunkSizeMs = 300,
                autoSilenceMs = 900,
                autoMaxDurationMs = 30000,
                sessionTimeoutMs = 0,
                warmupEnabled = false,
                highPassCutoffHz = 0,
                zcrEnabled = false
            ),
            "NOISY" to RuntimeSttConfig(
                energyThreshold = 0.0030f,
                preRollMs = 150,
                stableChunkSizeMs = 600,
                autoSilenceMs = 1400,
                autoMaxDurationMs = 30000,
                sessionTimeoutMs = 0,
                warmupEnabled = true,
                warmupDurationMs = 150,
                highPassCutoffHz = 120,
                zcrEnabled = true
            ),
            "MOBILE" to RuntimeSttConfig(
                energyThreshold = 0.0020f,
                preRollMs = 120,
                stableChunkSizeMs = 500,
                autoSilenceMs = 1100,
                autoMaxDurationMs = 30000,
                sessionTimeoutMs = 0,
                warmupEnabled = true,
                warmupDurationMs = 100,
                highPassCutoffHz = 80,
                zcrEnabled = true
            ),
            "DESKTOP" to RuntimeSttConfig(
                energyThreshold = 0.0015f,
                preRollMs = 70,
                stableChunkSizeMs = 300,
                autoSilenceMs = 800,
                autoMaxDurationMs = 30000,
                sessionTimeoutMs = 0,
                warmupEnabled = false,
                highPassCutoffHz = 0,
                zcrEnabled = false
            ),
            "CONVERSATIONAL" to RuntimeSttConfig(
                energyThreshold = 0.0018f,
                preRollMs = 100,
                stableChunkSizeMs = 400,
                autoSilenceMs = 1600,
                autoMaxDurationMs = 60000,
                sessionTimeoutMs = 2000,
                warmupEnabled = false,
                highPassCutoffHz = 0,
                zcrEnabled = false
            ),
            "COMMAND" to RuntimeSttConfig(
                energyThreshold = 0.0025f,
                preRollMs = 60,
                stableChunkSizeMs = 200,
                autoSilenceMs = 600,
                autoMaxDurationMs = 5000,
                sessionTimeoutMs = 0,
                warmupEnabled = false,
                highPassCutoffHz = 100,
                zcrEnabled = true
            )
        )

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
                zcrEnabled = sttCfg.zcrEnabled,
                sttMode = sttCfg.sttMode,
                grammar = sttCfg.grammar,
                partialsEnabled = sttCfg.partialsEnabled,
                autoReturn = sttCfg.autoReturn
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

