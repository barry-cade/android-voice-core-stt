package dev.barrycade.voicecore.stt

/**
 * Shared configuration fields common across all STT strategies.
 *
 * These values apply regardless of whether the active mode is
 * manual/manual or manual/auto.
 *
 * @property energyThreshold VAD energy threshold for speech detection.
 * @property preRollMs Pre-roll window before speech is accepted (ms).
 * @property stableChunkSizeMs Chunk size used for stable frame processing (ms).
 * @property debugLoggingEnabled Enable verbose debug logging.
 */
data class SharedSttConfig(
    val energyThreshold: Float = 0.03f,
    val preRollMs: Int = 100,
    val stableChunkSizeMs: Int = 500,
    val debugLoggingEnabled: Boolean = false
)
