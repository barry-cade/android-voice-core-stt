package dev.barrycade.voicecore.stt

/**
 * Single configuration object for an STT session.
 *
 * Every field is required — no defaults, no optional fields, no inference.
 * The strategy-specific config is passed as [strategySpecific] with an
 * enforced type contract (see [SttRunConfigValidator]):
 *
 * - If [ttsLifeCycleStrategy] == [SttLifeCycleStrategy.MANUAL_MANUAL],
 *   [strategySpecific] must be [ManualManualSpecific].
 * - If [ttsLifeCycleStrategy] == [SttLifeCycleStrategy.MANUAL_AUTO],
 *   [strategySpecific] must be [ManualAutoSpecific].
 *
 * Any other type will be rejected.
 *
 * @property ttsEngineConfig Engine-level configuration (model path, language, timing).
 * @property ttsLifeCycleStrategy Determines how recording starts and stops.
 * @property strategySpecific Mode-specific parameters, typed per [ttsLifeCycleStrategy].
 */
data class SttRunConfig(
    val ttsEngineConfig: TtsEngineConfig,
    val ttsLifeCycleStrategy: SttLifeCycleStrategy,
    val strategySpecific: Any
)
