package dev.barrycade.voicecore.stt

/**
 * Strategy-specific configuration for [SttLifeCycleStrategy.MANUAL_MANUAL].
 *
 * Every field is required — no defaults, no optional fields, no inference.
 *
 * Extended with [drainMode] to control whether the session buffer starts
 * fresh ([DrainMode.DRAIN_FROM_NEXT_FRAME]) or includes pre-begin audio
 * ([DrainMode.DRAIN_FROM_HEAD]). Default is [DrainMode.DRAIN_FROM_NEXT_FRAME].
 *
 * @property energyThreshold VAD energy threshold for speech detection. Must be > 0.
 * @property maxDurationMs Maximum allowed speech duration before forced stop (ms). Must be > 0.
 * @property abnormalSilenceMs Silence duration treated as abnormal silence (ms). Must be > 0.
 * @property drainMode Drain mode for PCM buffering. Default: [DrainMode.DRAIN_FROM_NEXT_FRAME].
 */
data class ManualManualSpecific(
    val energyThreshold: Float,
    val maxDurationMs: Int,
    val abnormalSilenceMs: Int,
    val drainMode: DrainMode = DrainMode.DRAIN_FROM_NEXT_FRAME
)

