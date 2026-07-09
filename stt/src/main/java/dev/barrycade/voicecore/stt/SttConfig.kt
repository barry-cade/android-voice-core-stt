package dev.barrycade.voicecore.stt

// TODO(major-version): Remove legacy config path after full migration to SttRunConfig.

/**
 * Legacy configuration for [SpeechToText.create].
 *
 * This type is deprecated. New integrations should use [SttRunConfig]
 * with [SpeechToText.setConfig] and [SpeechToText.startSession] instead.
 *
 * This type will be removed in a future major version.
 * It remains fully functional for existing code.
 */
@Deprecated(
    message = "Use SttRunConfig instead. This type will be removed in a future major version.",
    level = DeprecationLevel.WARNING
)
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

    /** @deprecated Use [SttLifeCycleStrategy] instead. */
    @Deprecated(
        message = "Use SttLifeCycleStrategy instead. This field will be removed in a future major version.",
        level = DeprecationLevel.WARNING
    )
    val startStrategy: String = "manual",

    /** @deprecated Use [SttLifeCycleStrategy] instead. */
    @Deprecated(
        message = "Use SttLifeCycleStrategy instead. This field will be removed in a future major version.",
        level = DeprecationLevel.WARNING
    )
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
            manualAuto = manualAuto
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

// TODO(major-version): Remove legacy config path after full migration to SttRunConfig.

/**
 * Configurable timing for manual/manual mode.
 *
 * @property maxDurationMs Max allowed speech duration before forced stop (ms).
 * @property abnormalSilenceMs Silence duration treated as "forgot to press STOP" (ms).
 *
 * This type is deprecated. New integrations should use [ManualManualSpecific]
 * inside [SttRunConfig] instead.
 * It will be removed in a future major version.
 */
@Deprecated(
    message = "Use ManualManualSpecific instead. This type will be removed in a future major version.",
    level = DeprecationLevel.WARNING
)
data class ManualManualConfig(
    val maxDurationMs: Int = 30000,
    val abnormalSilenceMs: Int = 5000
)

// TODO(major-version): Remove legacy config path after full migration to SttRunConfig.

/**
 * Configurable timing for manual/auto (auto-silence) mode.
 *
 * @property maxDurationMs Max allowed speech duration before forced stop (ms).
 * @property autoSilenceMs Normal auto-silence threshold (ms).
 *
 * This type is deprecated. New integrations should use [ManualAutoSpecific]
 * inside [SttRunConfig] instead.
 * It will be removed in a future major version.
 */
@Deprecated(
    message = "Use ManualAutoSpecific instead. This type will be removed in a future major version.",
    level = DeprecationLevel.WARNING
)
data class ManualAutoConfig(
    val maxDurationMs: Int = 30000,
    val autoSilenceMs: Int = 1200
)

// TODO(major-version): Remove legacy config path after full migration to SttRunConfig.

/**
 * Configurable reason messages returned when an utterance is discarded.
 *
 * @property tooLong Message when max duration is exceeded.
 * @property abnormalSilence Message when abnormal silence is detected (manual/manual only).
 *
 * This type is deprecated. Reason messages are not part of the new [SttRunConfig]
 * API and will be removed in a future major version.
 */
@Deprecated(
    message = "Reason messages are removed from the new API. This type will be removed in a future major version.",
    level = DeprecationLevel.WARNING
)
data class ReasonMessages(
    val tooLong: String = "You spoke for too long.",
    val abnormalSilence: String = "You stopped speaking for too long."
)

