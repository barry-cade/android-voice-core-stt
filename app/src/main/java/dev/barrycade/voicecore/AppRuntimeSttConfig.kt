package dev.barrycade.voicecore

// TODO(major-version): Remove legacy config path after full migration to SttRunConfig.

data class AppRuntimeSttConfig(
    val energyThreshold: Float,
    val preRollMs: Int,
    val stableChunkSizeMs: Int,
    val manualManual: AppManualManualConfig = AppManualManualConfig(),
    val manualAuto: AppManualAutoConfig = AppManualAutoConfig(),
    val reasonMessages: AppReasonMessages = AppReasonMessages(),
    val debugLoggingEnabled: Boolean = true,
    val startStrategy: String = "manual",
    val stopStrategy: String = "manual"
)

data class AppManualManualConfig(
    val maxDurationMs: Int = 30000,
    val abnormalSilenceMs: Int = 5000
)

data class AppManualAutoConfig(
    val maxDurationMs: Int = 30000,
    val autoSilenceMs: Int = 1200
)

data class AppReasonMessages(
    val tooLong: String = "You spoke for too long.",
    val abnormalSilence: String = "You stopped speaking for too long."
)


