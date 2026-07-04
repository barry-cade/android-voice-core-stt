package dev.barrycade.voicecore.stt

/**
 * Immutable timing snapshot for a single utterance transcription cycle.
 * All values are in milliseconds. All fields are final and set at construction time.
 *
 * @property vadActiveMs Total time VAD reported SPEECH (ms).
 * @property utteranceDurationMs Total duration of the utterance from first speech frame (ms).
 * @property silencePaddingMs Silence padding applied after speech end to trigger utterance end (ms).
 * @property preRollMs Pre-roll duration configured for the pipeline (ms).
 * @property inferenceMs Whisper inference duration (ms).
 * @property ttsHandoffMs TTS handoff time, if applicable (null for STT-only builds) (ms).
 * @property totalPipelineMs End-to-end time from utterance start to final transcript (ms).
 */
data class SttTimingSnapshot(
    val vadActiveMs: Long,
    val utteranceDurationMs: Long,
    val silencePaddingMs: Long,
    val preRollMs: Long,
    val inferenceMs: Long,
    val ttsHandoffMs: Long? = null,
    val totalPipelineMs: Long
)
