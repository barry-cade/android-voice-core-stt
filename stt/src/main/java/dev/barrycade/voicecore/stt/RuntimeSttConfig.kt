package dev.barrycade.voicecore.stt

/**
 * Full runtime configuration for the STT pipeline.
 *
 * Shared fields are grouped in [shared] for clarity. Timing fields are
 * mode-specific to prevent cross-mode interference; use [manualManual]
 * or [manualAuto] depending on the active stop strategy.
 */
internal data class RuntimeSttConfig(
    val shared: SharedSttConfig = SharedSttConfig(),
    val manualManual: ManualManualConfig = ManualManualConfig(),
    val manualAuto: ManualAutoConfig = ManualAutoConfig(),
    val reasonMessages: ReasonMessages = ReasonMessages()
) {
    /** Convenience accessors for shared fields. */
    val energyThreshold: Float get() = shared.energyThreshold
    val preRollMs: Int get() = shared.preRollMs
    val stableChunkSizeMs: Int get() = shared.stableChunkSizeMs
    val debugLoggingEnabled: Boolean get() = shared.debugLoggingEnabled
}
internal fun RuntimeSttConfig.validate() {
    require(energyThreshold in 0.0001f..1f) {
        "energyThreshold=$energyThreshold must be in [0.0001, 1]"
    }

    require(preRollMs in 0..2000) {
        "preRollMs=$preRollMs must be in [0, 2000] ms"
    }

    require(stableChunkSizeMs in 50..2000) {
        "stableChunkSizeMs=$stableChunkSizeMs must be in [50, 2000] ms"
    }

    require(manualManual.maxDurationMs in 1000..60000) {
        "manualManual.maxDurationMs=${manualManual.maxDurationMs} must be in [1000, 60000] ms"
    }

    require(manualManual.abnormalSilenceMs in 50..30000) {
        "manualManual.abnormalSilenceMs=${manualManual.abnormalSilenceMs} must be in [50, 30000] ms"
    }

    require(manualAuto.maxDurationMs in 1000..60000) {
        "manualAuto.maxDurationMs=${manualAuto.maxDurationMs} must be in [1000, 60000] ms"
    }

    require(manualAuto.autoSilenceMs in 50..10000) {
        "manualAuto.autoSilenceMs=${manualAuto.autoSilenceMs} must be in [50, 10000] ms"
    }
}

