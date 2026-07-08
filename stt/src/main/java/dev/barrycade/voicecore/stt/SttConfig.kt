package dev.barrycade.voicecore.stt

data class SttConfig(
    val energyThreshold: Float = 0.03f,

    /** @deprecated Use [manualManual] or [manualAuto] mode-specific config instead. */
    val silencePaddingMs: Int = 1200,

    val preRollMs: Int = 100,

    /** @deprecated Use [manualManual] or [manualAuto] mode-specific config instead. */
    val maxUtteranceLengthMs: Int = 30000,

    val stableChunkSizeMs: Int = 500,

    /** @deprecated Use [manualManual] or [manualAuto] mode-specific config instead. */
    val motionModeEnergyThreshold: Float = 0.05f,

    /** @deprecated Use [manualManual] or [manualAuto] mode-specific config instead. */
    val motionModeSilencePaddingMs: Int = 300,

    val debugLoggingEnabled: Boolean = false,
    val modelPath: String,

    val startStrategy: String = "manual",
    val stopStrategy: String = "manual",

    // ── Mode-specific config blocks ────────────────────────────────────────
    val manualManual: ManualManualConfig = ManualManualConfig(),
    val manualAuto: ManualAutoConfig = ManualAutoConfig(),
    val reasonMessages: ReasonMessages = ReasonMessages()
) {
    /**
     * Convert this public config into the internal [RuntimeSttConfig].
     */
    internal fun toRuntimeConfig(): RuntimeSttConfig {
        return RuntimeSttConfig(
            shared = SharedSttConfig(
                energyThreshold = energyThreshold,
                preRollMs = preRollMs,
                stableChunkSizeMs = stableChunkSizeMs,
                debugLoggingEnabled = debugLoggingEnabled
            ),
            manualManual = manualManual,
            manualAuto = manualAuto,
            reasonMessages = reasonMessages
        )
    }
    /**
     * Resolve the [startStrategy] string to a [StartTriggerStrategy] instance.
     *
     * Allowed values:
     *   "manual"     → [ManualStartTrigger] (explicit caller request)
     *   "autoSpeech" → reserved for future use
     *   "wakeWord"   → reserved for future use
     *
     * @throws IllegalArgumentException if the value is not recognised.
     */
    fun resolveStartTrigger(): StartTriggerStrategy {
        return when (startStrategy.lowercase()) {
            "manual" -> ManualStartTrigger()
            else -> throw IllegalArgumentException(
                "startStrategy='$startStrategy' is not a valid value. " +
                    "Allowed: manual"
            )
        }
    }
    /**
     * Resolve the [stopStrategy] string to a [StopTriggerStrategy] instance.
     *
     * Allowed values:
     *   "manual"      → [ManualStopTrigger] (explicit caller request)
     *   "autoSilence" → [AutoSilenceStopTrigger] (stop on silence exceeding VAD threshold)
     *   "wakeWord"    → reserved for future use
     *
     * When stopStrategy is "autoSilence", the silence threshold is taken from
     * [manualAuto.autoSilenceMs]. For "manual", abnormal silence is defined by
     * [manualManual.abnormalSilenceMs].
     *
     * @throws IllegalArgumentException if the value is not recognised.
     */
    fun resolveStopTrigger(): StopTriggerStrategy {
        return when (stopStrategy.lowercase()) {
            "manual" -> ManualStopTrigger()
            "autosilence" -> AutoSilenceStopTrigger(
                silenceThresholdMs = this.manualAuto.autoSilenceMs.toLong()
            )
            else -> throw IllegalArgumentException(
                "stopStrategy='$stopStrategy' is not a valid value. " +
                    "Allowed: manual, autoSilence"
            )
        }
    }
}

/**
 * Configurable timing for manual/manual mode.
 *
 * @property maxDurationMs Max allowed speech duration before forced stop (ms).
 * @property abnormalSilenceMs Silence duration treated as "forgot to press STOP" (ms).
 */
data class ManualManualConfig(
    val maxDurationMs: Int = 30000,
    val abnormalSilenceMs: Int = 5000
)

/**
 * Configurable timing for manual/auto (auto-silence) mode.
 *
 * @property maxDurationMs Max allowed speech duration before forced stop (ms).
 * @property autoSilenceMs Normal auto-silence threshold (ms).
 */
data class ManualAutoConfig(
    val maxDurationMs: Int = 30000,
    val autoSilenceMs: Int = 1200
)

/**
 * Configurable reason messages returned when an utterance is discarded.
 *
 * @property tooLong Message when max duration is exceeded.
 * @property abnormalSilence Message when abnormal silence is detected (manual/manual only).
 */
data class ReasonMessages(
    val tooLong: String = "You spoke for too long.",
    val abnormalSilence: String = "You stopped speaking for too long."
)

