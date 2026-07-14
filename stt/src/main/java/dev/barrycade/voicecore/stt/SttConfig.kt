@file:Suppress("DEPRECATION")
package dev.barrycade.voicecore.stt

/**
 * Immutable configuration for an STT session.
 *
 * Every field is required — no defaults, no optional fields, no inference.
 *
 * ## Strategy model
 *
 * Start and stop are independent orthogonal axes, both controlled
 * by sealed strategy types:
 * - [startTrigger] defines when capture begins.
 * - [stopTrigger] defines when capture ends.
 *
 * ## Construction
 *
 * Use the DSL-style factory function:
 * ```
 * SttConfig(
 *     modelPath = "/path/to/model.bin",
 *     language = "en",
 *     energyThreshold = 0.03f,
 *     preRollMs = 100,
 *     stableChunkSizeMs = 500,
 *     drainMode = DrainMode.DRAIN_FROM_NEXT_FRAME,
 *     startTrigger = StartTrigger.Manual,
 *     stopTrigger = StopTrigger.Manual
 * )
 * ```
 *
 * @property modelPath Absolute file path to the Whisper model binary.
 * @property language Language code for transcription (e.g. "en").
 * @property debugLoggingEnabled When true, detailed debug logging is enabled.
 * @property energyThreshold RMS energy threshold for VAD speech detection.
 * @property preRollMs Duration of pre-roll PCM to keep before utterance starts (ms).
 * @property stableChunkSizeMs Duration of stable speech chunk to confirm utterance (ms).
 * @property drainMode PCM drain mode for capture start.
 * @property startTrigger Defines when capture begins.
 * @property stopTrigger Defines when capture ends.
 * @property warmupEnabled Whether to run Whisper warm-up on model load.
 * @property warmupDurationMs Duration of warm-up inference in ms.
 * @property bufferSizeSamples Size of the AudioRecord read buffer in samples.
 *        Must be >= 1024 and <= 16000. Default 4000 (0.25s at 16kHz).
 */
internal data class SttConfig(
    val modelPath: String,
    val language: String,
    val debugLoggingEnabled: Boolean = false,
    val energyThreshold: Float,
    val preRollMs: Int,
    val stableChunkSizeMs: Int,
    val drainMode: DrainMode,
    val startTrigger: StartTrigger,
    val stopTrigger: StopTrigger,
    val warmupEnabled: Boolean = false,
    val warmupDurationMs: Int = 0,
    val bufferSizeSamples: Int = 4000
) {
    init {
        require(energyThreshold in 0.0001f..1f) {
            "energyThreshold=$energyThreshold must be in [0.0001, 1]"
        }
        require(preRollMs in 0..2000) {
            "preRollMs=$preRollMs must be in [0, 2000] ms"
        }
        require(stableChunkSizeMs in 50..2000) {
            "stableChunkSizeMs=$stableChunkSizeMs must be in [50, 2000] ms"
        }
        require(bufferSizeSamples in 1024..16000) {
            "bufferSizeSamples=$bufferSizeSamples must be in [1024, 16000]"
        }
    }
}

/**
 * Sealed interface for start trigger strategies.
 *
 * @see Manual Start begins on explicit caller request.
 * @see VadStart Start begins when VAD detects sustained speech.
 * @see WakeWordStart Start begins when a wake word is detected.
 */
internal sealed interface StartTrigger {
    /** Start begins on explicit caller request via [SpeechToText.startSession]. */
    data object Manual : StartTrigger

    /**
     * Start begins when VAD detects sustained speech above a threshold.
     *
     * @property vadStartThreshold Energy threshold for VAD-based start.
     * @property minSpeechMs Minimum consecutive speech ms for VAD-based start.
     */
    data class VadStart(
        val vadStartThreshold: Float,
        val minSpeechMs: Int
    ) : StartTrigger

    /**
     * Start begins when a wake word is detected.
     *
     * @property wakeWord Wake word phrase.
     * @property confidenceThreshold Detection confidence threshold.
     */
    data class WakeWordStart(
        val wakeWord: String,
        val confidenceThreshold: Float
    ) : StartTrigger
}

/**
 * Sealed interface for stop trigger strategies.
 *
 * @see Manual Stop occurs on explicit caller request.
 * @see AutoSilence Stop occurs after sustained silence or max duration.
 * @see Duration Stop occurs after a fixed maximum duration.
 */
internal sealed interface StopTrigger {
    /** Stop occurs on explicit caller request via [SpeechToText.stopAndTranscribe]. */
    data object Manual : StopTrigger

    /**
     * Stop occurs after sustained silence or max duration.
     *
     * @property silenceMs Silence duration that triggers stop (ms).
     * @property maxDurationMs Maximum allowed speech duration (ms).
     */
    data class AutoSilence(
        val silenceMs: Int,
        val maxDurationMs: Int
    ) : StopTrigger

    /**
     * Stop occurs after a fixed maximum duration.
     *
     * @property maxDurationMs Maximum allowed session duration (ms).
     */
    data class Duration(
        val maxDurationMs: Int
    ) : StopTrigger
}
