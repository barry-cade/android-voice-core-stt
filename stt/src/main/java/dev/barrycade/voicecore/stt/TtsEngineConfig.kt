package dev.barrycade.voicecore.stt

/**
 * Engine-level configuration for the STT pipeline.
 *
 * Every field is required — no defaults, no optional fields, no inference.
 *
 * @property modelPath Absolute file path to the Whisper model binary.
 * @property language Language code for transcription (e.g. "en").
 * @property preRollMs Pre-roll window of audio buffered before speech detection (ms). Must be >= 0.
 * @property stableChunkSizeMs Chunk size for stable frame processing (ms). Must be >= 0.
 * @property debugLoggingEnabled Whether verbose debug logging is enabled.
 */
data class TtsEngineConfig(
    val modelPath: String,
    val language: String,
    val preRollMs: Int,
    val stableChunkSizeMs: Int,
    val debugLoggingEnabled: Boolean
)
