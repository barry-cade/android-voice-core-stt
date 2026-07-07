package dev.barrycade.voicecore.stt

data class SttConfig(
    val energyThreshold: Float = 0.03f,
    val silencePaddingMs: Int = 600,
    val preRollMs: Int = 100,
    val maxUtteranceLengthMs: Int = 7000,
    val stableChunkSizeMs: Int = 500,
    val motionModeEnergyThreshold: Float = 0.05f,
    val motionModeSilencePaddingMs: Int = 300,
    val debugLoggingEnabled: Boolean = false,
    val modelPath: String,
    val startStrategy: String = "manual",
    val stopStrategy: String = "manual"
) {
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
     *   "autoSilence" → reserved for future use
     *   "wakeWord"    → reserved for future use
     *
     * @throws IllegalArgumentException if the value is not recognised.
     */
    fun resolveStopTrigger(): StopTriggerStrategy {
        return when (stopStrategy.lowercase()) {
            "manual" -> ManualStopTrigger()
            else -> throw IllegalArgumentException(
                "stopStrategy='$stopStrategy' is not a valid value. " +
                    "Allowed: manual"
            )
        }
    }
}

