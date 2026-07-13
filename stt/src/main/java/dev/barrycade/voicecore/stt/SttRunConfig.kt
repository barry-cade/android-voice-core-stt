package dev.barrycade.voicecore.stt

/**
 * Single configuration object for an STT session.
 *
 * Every field is required — no defaults, no optional fields, no inference.
 *
 * Start and stop are independent orthogonal axes:
 * - [startStrategy] defines when capture begins
 * - [stopStrategy] defines when capture ends
 * - [drainMode] defines how the PCM buffer is handled at begin time
 * - [vadConfig] defines VAD parameters shared by both strategies
 * - [warmupEnabled] controls whether Whisper warm-up is performed
 * - [warmupDurationMs] duration of the warm-up inference (ms)
 * - [bufferSizeSamples] size of the AudioRecord read buffer in samples
 *
 * @property ttsEngineConfig Engine-level configuration (model path, language).
 * @property vadConfig VAD parameters (energy threshold, pre-roll, chunk size).
 * @property drainMode Drain mode for PCM buffering at begin time.
 * @property startStrategy Start strategy config (type + params).
 * @property stopStrategy Stop strategy config (type + params).
 * @property warmupEnabled Whether to run Whisper warm-up on model load.
 * @property warmupDurationMs Duration of warm-up inference in ms.
 * @property bufferSizeSamples Size of the AudioRecord read buffer in samples.
 *        Must be >= 1024 and <= 16000. Default 4000 (0.25s at 16kHz).
 */
data class SttRunConfig(
    val ttsEngineConfig: TtsEngineConfig,
    val vadConfig: VadConfig,
    val drainMode: DrainMode,
    val startStrategy: StartStrategyConfig,
    val stopStrategy: StopStrategyConfig,
    val warmupEnabled: Boolean = false,
    val warmupDurationMs: Int = 0,
    val bufferSizeSamples: Int = 4000
)

/**
 * Start strategy configuration.
 *
 * @property type Start trigger type.
 * @property vadStartThreshold Energy threshold for VAD-based start (required for VAD_START).
 * @property minSpeechMs Minimum consecutive speech ms for VAD-based start (required for VAD_START).
 * @property wakeWord Wake word phrase for WAKEWORD start (required for WAKEWORD).
 * @property confidenceThreshold Detection confidence threshold for WAKEWORD start (required for WAKEWORD).
 */
data class StartStrategyConfig(
    val type: String,
    val vadStartThreshold: Float? = null,
    val minSpeechMs: Int? = null,
    val wakeWord: String? = null,
    val confidenceThreshold: Float? = null
)

/**
 * Stop strategy configuration.
 *
 * @property type Stop trigger type.
 * @property silenceMs Silence duration that triggers stop (ms). Required for AUTO_SILENCE.
 * @property maxDurationMs Maximum allowed speech duration (ms). Required for AUTO_SILENCE, DURATION.
 */
data class StopStrategyConfig(
    val type: String,
    val silenceMs: Int? = null,
    val maxDurationMs: Int? = null
)

