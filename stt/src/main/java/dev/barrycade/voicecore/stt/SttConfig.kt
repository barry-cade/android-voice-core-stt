package dev.barrycade.voicecore.stt

data class SttConfig(
    val energyThreshold: Float = 0.03f,
    val silencePaddingMs: Int = 600,
    val preRollMs: Int = 100,
    val maxUtteranceLengthMs: Int = 7000,
    val stableChunkSizeMs: Int = 500,
    val motionModeEnergyThreshold: Float = 0.05f,
    val motionModeSilencePaddingMs: Int = 300,
    val debugLoggingEnabled: Boolean = false,
    val modelPath: String
)

