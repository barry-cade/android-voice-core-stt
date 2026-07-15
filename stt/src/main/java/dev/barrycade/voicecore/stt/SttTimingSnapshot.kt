package dev.barrycade.voicecore.stt

/**
 * Immutable timing snapshot for a single utterance transcription cycle.
 * All values are in milliseconds. All fields are final and set at construction time.
 *
 * @property vadActiveMs Total time VAD reported SPEECH (ms).
 * @property utteranceDurationMs Total duration of the utterance from first speech frame (ms).
 * @property captureMs Wall-clock duration of PCM capture (ms).
 * @property silencePaddingMs Silence padding applied after speech end to trigger utterance end (ms).
 * @property preRollMs Pre-roll duration configured for the pipeline (ms).
 * @property inferenceMs Whisper inference duration (ms).
 * @property ttsHandoffMs TTS handoff time, if applicable (null for STT-only builds) (ms).
 * @property totalPipelineMs End-to-end time from utterance start to final transcript (ms).
 * @property vadConfidence VAD confidence at utterance end (nullable, diagnostic only).
 * @property avgRms Average RMS over the sampling window at utterance end (nullable).
 * @property peakRms Peak RMS over the sampling window at utterance end (nullable).
 * @property noiseFloorRms Noise floor RMS estimate at utterance end (nullable).
 */
internal data class SttTimingSnapshot(
    val vadActiveMs: Long,
    val utteranceDurationMs: Long,
    val captureMs: Long = 0L,
    val silencePaddingMs: Long,
    val preRollMs: Long,
    val inferenceMs: Long,
    val ttsHandoffMs: Long? = null,
    val totalPipelineMs: Long,
    val vadConfidence: Float? = null,
    val avgRms: Float? = null,
    val peakRms: Float? = null,
    val noiseFloorRms: Float? = null
)

