package dev.barrycade.voicecore.stt

internal data class RuntimeSttConfig(
    val energyThreshold: Float,
    val silencePaddingMs: Int,
    val preRollMs: Int,
    val maxUtteranceLengthMs: Int,
    val stableChunkSizeMs: Int,
    val highPassCutoffHz: Int,
    val motionMode: MotionModeConfig,
    val debugLoggingEnabled: Boolean = false
)

internal data class MotionModeConfig(
    val energyThreshold: Float,
    val silencePaddingMs: Int
)

internal fun RuntimeSttConfig.validate() {
    require(energyThreshold in 0.0001f..1f) {
        "energyThreshold=$energyThreshold must be in [0.0001, 1]"
    }

    require(silencePaddingMs in 50..5000) {
        "silencePaddingMs=$silencePaddingMs must be in [50, 5000] ms"
    }

    require(preRollMs in 0..2000) {
        "preRollMs=$preRollMs must be in [0, 2000] ms"
    }

    require(maxUtteranceLengthMs in 1000..20000) {
        "maxUtteranceLengthMs=$maxUtteranceLengthMs must be in [1000, 20000] ms"
    }

    require(stableChunkSizeMs in 50..2000) {
        "stableChunkSizeMs=$stableChunkSizeMs must be in [50, 2000] ms"
    }

    require(highPassCutoffHz in 0..2000) {
        "highPassCutoffHz=$highPassCutoffHz must be in [0, 2000] Hz"
    }

    require(motionMode.energyThreshold in 0.0001f..1f) {
        "motionMode.energyThreshold=${motionMode.energyThreshold} must be in [0.0001, 1]"
    }

    require(motionMode.silencePaddingMs in 50..5000) {
        "motionMode.silencePaddingMs=${motionMode.silencePaddingMs} must be in [50, 5000] ms"
    }
}
