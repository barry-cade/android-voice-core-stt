package dev.barrycade.voicecore.stt

/**
 * VAD (Voice Activity Detection) configuration.
 *
 * Shared by start and stop strategies — both reference [energyThreshold],
 * while [preRollMs] is used at start time and [stableChunkSizeMs] at stop time.
 *
 * Every field is required — no defaults, no optional fields, no inference.
 *
 * @property energyThreshold RMS energy threshold for speech detection. Must be > 0.
 * @property preRollMs Pre-roll window before speech detection (ms). Must be >= 0.
 * @property stableChunkSizeMs Chunk size for stable frame processing (ms). Must be >= 0.
 */
data class VadConfig(
    val energyThreshold: Float,
    val preRollMs: Int,
    val stableChunkSizeMs: Int
)
