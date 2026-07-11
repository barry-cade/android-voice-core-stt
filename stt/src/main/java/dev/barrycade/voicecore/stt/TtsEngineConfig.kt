package dev.barrycade.voicecore.stt

/**
 * Engine-level configuration for the STT pipeline.
 *
 * Every field is required — no defaults, no optional fields, no inference.
 *
 * @property modelPath Absolute file path to the Whisper model binary.
 * @property language Language code for transcription (e.g. "en").
 * @property debugLoggingEnabled Whether verbose debug logging is enabled.
 */
data class TtsEngineConfig(
    val modelPath: String,
    val language: String,
    val debugLoggingEnabled: Boolean
)
