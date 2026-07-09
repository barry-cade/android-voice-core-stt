package dev.barrycade.voicecore

import android.content.Context
import android.util.Log
import dev.barrycade.voicecore.stt.ManualAutoSpecific
import dev.barrycade.voicecore.stt.ManualManualSpecific
import dev.barrycade.voicecore.stt.SttLifeCycleStrategy
import dev.barrycade.voicecore.stt.SttRunConfig
import dev.barrycade.voicecore.stt.TtsEngineConfig
import org.json.JSONObject

/**
 * Loads [SttRunConfig] from a new-format JSON config asset.
 *
 * Reads the config file specified by [configFileName] (e.g. "stt_config_manual_manual.json").
 * The JSON format is:
 * ```json
 * {
 *   "modelPath": "/path/to/model",
 *   "language": "en",
 *   "ttsEngineConfig": {
 *     "energyThreshold": 0.03,
 *     "preRollMs": 100,
 *     "stableChunkSizeMs": 500,
 *     "debugLoggingEnabled": false
 *   },
 *   "lifeCycleStrategy": "MANUAL_MANUAL",
 *   "strategySpecific": {
 *     "maxDurationMs": 30000,
 *     "maxSilenceMs": 5000
 *   }
 * }
 * ```
 *
 * Does NOT reference any legacy config types or fields (startStrategy, stopStrategy,
 * manualManual, manualAuto, reasonMessages).
 */
object AppSttConfigLoader {
    private const val TAG = "STT_CONFIG"

    /**
     * Load and construct a fully validated [SttRunConfig] from the given JSON asset file.
     *
     * @param context Android context for asset access.
     * @param configFileName Asset file name (e.g. "stt_config_manual_manual.json").
     * @param modelPath Override absolute file path to the Whisper model binary.
     * @param language Override language code for transcription (e.g. "en").
     * @return A fully constructed [SttRunConfig].
     * @throws IllegalStateException if the JSON is missing required fields.
     */
    fun loadSttRunConfig(
        context: Context,
        configFileName: String,
        modelPath: String,
        language: String
    ): SttRunConfig {
        val inputStream = context.assets.open(configFileName)
        val json = inputStream.use { it.readBytes().toString(Charsets.UTF_8) }

        return try {
            parseSttRunConfig(json, modelPath, language)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid STT configuration in $configFileName", e)
            throw IllegalStateException("Invalid STT configuration: ${e.message}", e)
        }
    }

    /**
     * Parse JSON string into a [SttRunConfig] using the new format.
     */
    private fun parseSttRunConfig(
        json: String,
        modelPath: String,
        language: String
    ): SttRunConfig {
        val root = JSONObject(json)

        // ── ttsEngineConfig block (fail fast if missing) ──────────────────
        val engineObj = root.getJSONObject("ttsEngineConfig")
        val energyThreshold = engineObj.getDouble("energyThreshold").toFloat()
        val preRollMs = engineObj.getInt("preRollMs")
        val stableChunkSizeMs = engineObj.getInt("stableChunkSizeMs")
        val debugLoggingEnabled = engineObj.optBoolean("debugLoggingEnabled", false)

        // ── Build TtsEngineConfig ─────────────────────────────────────────
        val engineConfig = TtsEngineConfig(
            modelPath = modelPath,
            language = language,
            preRollMs = preRollMs,
            stableChunkSizeMs = stableChunkSizeMs,
            debugLoggingEnabled = debugLoggingEnabled
        )

        // ── Determine lifecycle strategy ──────────────────────────────────
        val lifeCycleStrategyStr = root.getString("lifeCycleStrategy")
        val lifeCycleStrategy = when (lifeCycleStrategyStr) {
            "MANUAL_MANUAL" -> SttLifeCycleStrategy.MANUAL_MANUAL
            "MANUAL_AUTO" -> SttLifeCycleStrategy.MANUAL_AUTO
            else -> throw IllegalStateException(
                "Unsupported lifeCycleStrategy='$lifeCycleStrategyStr'. " +
                    "Allowed: MANUAL_MANUAL, MANUAL_AUTO."
            )
        }

        // ── Build strategy-specific config from strategySpecific block ────
        val specificObj = root.getJSONObject("strategySpecific")
        val maxDurationMs = specificObj.getInt("maxDurationMs")
        val maxSilenceMs = specificObj.getInt("maxSilenceMs")

        val strategySpecific: Any = when (lifeCycleStrategy) {
            SttLifeCycleStrategy.MANUAL_MANUAL -> {
                ManualManualSpecific(
                    energyThreshold = energyThreshold,
                    maxDurationMs = maxDurationMs,
                    abnormalSilenceMs = maxSilenceMs
                )
            }
            SttLifeCycleStrategy.MANUAL_AUTO -> {
                ManualAutoSpecific(
                    energyThreshold = energyThreshold,
                    maxDurationMs = maxDurationMs,
                    autoSilenceMs = maxSilenceMs
                )
            }
        }

        return SttRunConfig(
            ttsEngineConfig = engineConfig,
            ttsLifeCycleStrategy = lifeCycleStrategy,
            strategySpecific = strategySpecific
        )
    }
}
