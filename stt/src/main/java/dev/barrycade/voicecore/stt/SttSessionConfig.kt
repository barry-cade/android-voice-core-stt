@file:Suppress("DEPRECATION")
package dev.barrycade.voicecore.stt

/**
 * Immutable session configuration, built once during [SpeechToText.initStt].
 *
 * Bundles all config-derived values needed across the pipeline into a single
 * immutable value. No mutable fields — once constructed, the configuration
 * for a session is fixed.
 *
 * @property runtimeConfig The flattened runtime config with strategy instances.
 * @property drainMode PCM drain mode for capture start.
 * @property bufferSizeSamples AudioRecord read buffer size in samples.
 * @property modelPath Path to the Whisper model file.
 * @property warmupEnabled Whether Whisper warm-up is enabled.
 * @property warmupDurationMs Duration of warm-up inference (ms).
 */
internal data class SttSessionConfig(
    val runtimeConfig: RuntimeSttConfig,
    val drainMode: DrainMode,
    val bufferSizeSamples: Int,
    val modelPath: String,
    val warmupEnabled: Boolean,
    val warmupDurationMs: Int
) {
    companion object {
        /**
         * Build an immutable [SttSessionConfig] from a validated [SttRunConfig].
         *
         * Converts strategy config types into concrete strategy instances
         * and flattens all fields.
         */
        fun fromSttRunConfig(runConfig: SttRunConfig): SttSessionConfig {
            return SttSessionConfig(
                runtimeConfig = RuntimeSttConfig.fromSttRunConfig(runConfig),
                drainMode = runConfig.drainMode,
                bufferSizeSamples = runConfig.bufferSizeSamples,
                modelPath = runConfig.ttsEngineConfig.modelPath,
                warmupEnabled = runConfig.warmupEnabled,
                warmupDurationMs = runConfig.warmupDurationMs
            )
        }
    }
}
