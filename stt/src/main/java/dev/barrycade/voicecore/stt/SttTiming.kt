package dev.barrycade.voicecore.stt

/**
 * Internal timing diagnostics for a single utterance transcription cycle.
 *
 * @property pcmMs Total PCM capture duration for the utterance (ms).
 * @property vadActiveMs Total time VAD reported SPEECH (ms).
 * @property whisperMs Whisper inference duration (ms).
 * @property totalMs End-to-end time from utterance start to final transcript (ms).
 */
internal data class SttTiming(
    val pcmMs: Long = 0L,
    val vadActiveMs: Long = 0L,
    val whisperMs: Long = 0L,
    val totalMs: Long = 0L
)
