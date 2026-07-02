package dev.barrycade.voicecore

data class AppRuntimeSttConfig(
    val energyThreshold: Float,
    val silencePaddingMs: Int,
    val preRollMs: Int,
    val maxUtteranceLengthMs: Int,
    val stableChunkSizeMs: Int,
    val highPassCutoffHz: Int,
    val motionMode: AppMotionModeConfig
)

data class AppMotionModeConfig(
    val energyThreshold: Float,
    val silencePaddingMs: Int
)
