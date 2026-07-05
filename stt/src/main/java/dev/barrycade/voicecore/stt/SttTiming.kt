package dev.barrycade.voicecore.stt

/**
 * Structured timing snapshot for a single utterance transcription cycle.
 * All values are in milliseconds.
 */
internal data class SttTiming(
    val vadActiveMs: Int,
    val utteranceMs: Int,
    val inferenceMs: Int,
    val totalMs: Int
)
