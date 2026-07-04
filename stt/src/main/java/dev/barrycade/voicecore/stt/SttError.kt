package dev.barrycade.voicecore.stt

/**
 * Structured error object produced by every failure in the STT subsystem.
 *
 * @property category  High-level error category.
 * @property code  The closed [SttErrorCode] categorising this failure.
 * @property message  Human-readable description of what went wrong.
 * @property utteranceId  Optional sequential ID of the current utterance.
 * @property timingSnapshotMs  Optional timing snapshot at error time (millis map).
 * @property lastRms  Optional last RMS energy from VAD.
 * @property lastVadState  Optional last VAD speech state.
 * @property vadConfidence  Optional VAD confidence at error time.
 * @property avgRms  Optional average RMS at error time.
 * @property peakRms  Optional peak RMS at error time.
 * @property noiseFloorRms  Optional noise floor RMS at error time.
 * @property motionModeActive  Optional motion mode active state.
 * @property cause  Optional originating throwable.
 * @property context  Structured diagnostic fields (never raw exception dumps).
 */
data class SttError(
    val category: SttErrorCategory,
    val code: SttErrorCode,
    val message: String,
    val utteranceId: Int? = null,
    val timingSnapshotMs: Map<String, Long>? = null,
    val lastRms: Float? = null,
    val lastVadState: Boolean? = null,
    val vadConfidence: Float? = null,
    val avgRms: Float? = null,
    val peakRms: Float? = null,
    val noiseFloorRms: Float? = null,
    val motionModeActive: Boolean? = null,
    val cause: Throwable? = null,
    val context: Map<String, Any?> = emptyMap()
)
