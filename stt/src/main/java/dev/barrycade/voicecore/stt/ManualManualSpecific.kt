package dev.barrycade.voicecore.stt

/**
 * Strategy-specific configuration for [SttLifeCycleStrategy.MANUAL_MANUAL].
 *
 * Every field is required — no defaults, no optional fields, no inference.
 *
 * @property energyThreshold VAD energy threshold for speech detection. Must be > 0.
 * @property maxDurationMs Maximum allowed speech duration before forced stop (ms). Must be > 0.
 * @property abnormalSilenceMs Silence duration treated as abnormal silence (ms). Must be > 0.
 */
data class ManualManualSpecific(
    val energyThreshold: Float,
    val maxDurationMs: Int,
    val abnormalSilenceMs: Int
)
