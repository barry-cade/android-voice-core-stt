package dev.barrycade.voicecore

import android.content.Context
import android.util.Log
import dev.barrycade.voicecore.stt.DrainMode
import dev.barrycade.voicecore.stt.StartStrategyConfig
import dev.barrycade.voicecore.stt.StopStrategyConfig
import dev.barrycade.voicecore.stt.SttRunConfig
import dev.barrycade.voicecore.stt.TtsEngineConfig
import dev.barrycade.voicecore.stt.VadConfig
import org.json.JSONObject

/**
 * Loads [SttRunConfig] from a new-format JSON config asset.
 *
 * Reads the config file specified by [configFileName] (e.g. "stt_config_manual_manual.json").
 * The JSON format is:
 * ```json
 * {
 *   "ttsEngineConfig": {
 *     "modelPath": "/path/to/model",
 *     "language": "en",
 *     "debugLoggingEnabled": false
 *   },
 *   "vadConfig": {
 *     "energyThreshold": 0.03,
 *     "preRollMs": 100,
 *     "stableChunkSizeMs": 500
 *   },
 *   "drainMode": "DRAIN_FROM_NEXT_FRAME",
 *   "startStrategy": { "type": "MANUAL" },
 *   "stopStrategy": {
 *     "type": "AUTO_SILENCE",
 *     "silenceMs": 1200,
 *     "maxDurationMs": 30000
 *   }
 * }
 * ```
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

        // ── ttsEngineConfig block ─────────────────────────────────────────
        val engineObj = root.getJSONObject("ttsEngineConfig")
        val debugLoggingEnabled = engineObj.optBoolean("debugLoggingEnabled", false)

        val engineConfig = TtsEngineConfig(
            modelPath = modelPath,
            language = language,
            debugLoggingEnabled = debugLoggingEnabled
        )

        // ── vadConfig block ───────────────────────────────────────────────
        val vadObj = root.getJSONObject("vadConfig")
        val energyThreshold = vadObj.getDouble("energyThreshold").toFloat()
        val preRollMs = vadObj.getInt("preRollMs")
        val stableChunkSizeMs = vadObj.getInt("stableChunkSizeMs")

        val vadConfig = VadConfig(
            energyThreshold = energyThreshold,
            preRollMs = preRollMs,
            stableChunkSizeMs = stableChunkSizeMs
        )

        // ── drainMode ─────────────────────────────────────────────────────
        val drainModeString = root.getString("drainMode")
        val drainMode = try {
            DrainMode.valueOf(drainModeString)
        } catch (_: IllegalArgumentException) {
            throw IllegalStateException(
                "Invalid drainMode='$drainModeString'. Allowed: DRAIN_FROM_NEXT_FRAME, DRAIN_FROM_HEAD."
            )
        }

        // ── startStrategy block ───────────────────────────────────────────
        val startObj = root.getJSONObject("startStrategy")
        val startType = startObj.getString("type")
        val startStrategy = StartStrategyConfig(type = startType)

        // ── stopStrategy block ────────────────────────────────────────────
        val stopObj = root.getJSONObject("stopStrategy")
        val stopType = stopObj.getString("type")
        val stopStrategy = when (stopType) {
            "MANUAL" -> StopStrategyConfig(type = stopType)
            "AUTO_SILENCE" -> StopStrategyConfig(
                type = stopType,
                silenceMs = stopObj.getInt("silenceMs"),
                maxDurationMs = stopObj.getInt("maxDurationMs")
            )
            else -> throw IllegalStateException(
                "Unsupported stopStrategy.type='$stopType'. Allowed: MANUAL, AUTO_SILENCE."
            )
        }

        return SttRunConfig(
            ttsEngineConfig = engineConfig,
            vadConfig = vadConfig,
            drainMode = drainMode,
            startStrategy = startStrategy,
            stopStrategy = stopStrategy
        )
    }
}
