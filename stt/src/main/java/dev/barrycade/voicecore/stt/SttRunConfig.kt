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
 *
 * @property ttsEngineConfig Engine-level configuration (model path, language).
 * @property vadConfig VAD parameters (energy threshold, pre-roll, chunk size).
 * @property drainMode Drain mode for PCM buffering at begin time.
 * @property startStrategy Start strategy config (type + params).
 * @property stopStrategy Stop strategy config (type + params).
 */
data class SttRunConfig(
    val ttsEngineConfig: TtsEngineConfig,
    val vadConfig: VadConfig,
    val drainMode: DrainMode,
    val startStrategy: StartStrategyConfig,
    val stopStrategy: StopStrategyConfig
)

/**
 * Start strategy configuration.
 *
 * @property type Start trigger type: "MANUAL" (explicit caller request).
 *         Future: "VAD" (energy-based auto-start).
 */
data class StartStrategyConfig(
    val type: String
)

/**
 * Stop strategy configuration.
 *
 * @property type Stop trigger type: "MANUAL" (explicit caller request) or
 *         "AUTO_SILENCE" (silence-based auto-stop).
 * @property silenceMs Silence duration that triggers stop (ms). Required for AUTO_SILENCE.
 * @property maxDurationMs Maximum allowed speech duration (ms). Required for AUTO_SILENCE.
 */
data class StopStrategyConfig(
    val type: String,
    val silenceMs: Int? = null,
    val maxDurationMs: Int? = null
)
