package dev.barrycade.voicecore.stt

/**
 * Strategy-specific configuration for [SttLifeCycleStrategy.MANUAL_AUTO].
 *
 * Every field is required — no defaults, no optional fields, no inference.
 *
 * @property energyThreshold VAD energy threshold for speech detection. Must be > 0.
 * @property maxDurationMs Maximum allowed speech duration before forced stop (ms). Must be > 0.
 * @property autoSilenceMs Silence duration that triggers automatic stop (ms). Must be > 0.
 */
data class ManualAutoSpecific(
    val energyThreshold: Float,
    val maxDurationMs: Int,
    val autoSilenceMs: Int
)
