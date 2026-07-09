package dev.barrycade.voicecore.stt

// TODO(major-version): Remove legacy config path after full migration to SttRunConfig.

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
 *
 * This type is deprecated. Use [SttRunConfig] with [TtsEngineConfig] instead.
 * It will be removed in a future major version.
 */
@Deprecated(
    message = "Use SttRunConfig instead. This type will be removed in a future major version.",
    level = DeprecationLevel.WARNING
)
data class SharedSttConfig(
    val energyThreshold: Float = 0.03f,
    val preRollMs: Int = 100,
    val stableChunkSizeMs: Int = 500,
    val debugLoggingEnabled: Boolean = false
)
