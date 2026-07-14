package dev.barrycade.voicecore

import android.content.Context
import android.util.Log
import dev.barrycade.voicecore.stt.DrainMode
import dev.barrycade.voicecore.stt.SttConfig
import dev.barrycade.voicecore.stt.StartTrigger
import dev.barrycade.voicecore.stt.StopTrigger
import org.json.JSONObject

/**
 * Loads [SttConfig] from a new-format JSON config asset.
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
     * Load and construct a fully validated [SttConfig] from the given JSON asset file.
     *
     * @param context Android context for asset access.
     * @param configFileName Asset file name (e.g. "stt_config_manual_manual.json").
     * @param modelPath Override absolute file path to the Whisper model binary.
     * @param language Override language code for transcription (e.g. "en").
     * @return A fully constructed [SttConfig].
     * @throws IllegalStateException if the JSON is missing required fields.
     */
    fun loadConfig(
        context: Context,
        configFileName: String,
        modelPath: String,
        language: String
    ): SttConfig {
        val inputStream = context.assets.open(configFileName)
        val json = inputStream.use { it.readBytes().toString(Charsets.UTF_8) }

        return try {
            parseConfig(json, modelPath, language)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid STT configuration in $configFileName", e)
            throw IllegalStateException("Invalid STT configuration: ${e.message}", e)
        }
    }

    private fun parseStartTrigger(startObj: JSONObject, startType: String): StartTrigger {
        return when (startType) {
            "MANUAL" -> {
                validateNoExtraFields(
                    startObj,
                    listOf("vadStartThreshold", "minSpeechMs", "wakeWord", "confidenceThreshold"),
                    "MANUAL startStrategy"
                )
                StartTrigger.Manual
            }
            "VAD_START" -> {
                requireField(startObj, "vadStartThreshold", "VAD_START startStrategy")
                validateNoExtraFields(
                    startObj,
                    listOf("wakeWord", "confidenceThreshold"),
                    "VAD_START startStrategy",
                    "wake-word"
                )
                requireField(startObj, "minSpeechMs", "VAD_START startStrategy")
                StartTrigger.VadStart(
                    vadStartThreshold = startObj.getDouble("vadStartThreshold").toFloat(),
                    minSpeechMs = startObj.getInt("minSpeechMs")
                )
            }
            "WAKEWORD" -> {
                requireField(startObj, "wakeWord", "WAKEWORD startStrategy")
                requireField(startObj, "confidenceThreshold", "WAKEWORD startStrategy")
                validateNoExtraFields(
                    startObj,
                    listOf("vadStartThreshold", "minSpeechMs"),
                    "WAKEWORD startStrategy",
                    "VAD"
                )
                StartTrigger.WakeWordStart(
                    wakeWord = startObj.getString("wakeWord"),
                    confidenceThreshold = startObj.getDouble("confidenceThreshold").toFloat()
                )
            }
            else -> throw IllegalArgumentException("Unknown startStrategy type: $startType")
        }
    }

    private fun parseStopTrigger(stopObj: JSONObject, stopType: String): StopTrigger {
        return when (stopType) {
            "MANUAL" -> {
                validateNoExtraFields(
                    stopObj,
                    listOf("silenceMs", "maxDurationMs"),
                    "MANUAL stopStrategy"
                )
                StopTrigger.Manual
            }
            "AUTO_SILENCE" -> {
                requireField(stopObj, "silenceMs", "AUTO_SILENCE stopStrategy")
                requireField(stopObj, "maxDurationMs", "AUTO_SILENCE stopStrategy")
                StopTrigger.AutoSilence(
                    silenceMs = stopObj.getInt("silenceMs"),
                    maxDurationMs = stopObj.getInt("maxDurationMs")
                )
            }
            "DURATION" -> {
                validateNoExtraFields(stopObj, listOf("silenceMs"), "DURATION stopStrategy", "silenceMs")
                requireField(stopObj, "maxDurationMs", "DURATION stopStrategy")
                StopTrigger.Duration(
                    maxDurationMs = stopObj.getInt("maxDurationMs")
                )
            }
            else -> throw IllegalArgumentException("Unknown stopStrategy type: $stopType")
        }
    }

    private fun requireField(obj: JSONObject, field: String, strategyName: String) {
        if (!obj.has(field)) {
            throw IllegalArgumentException("$field is required for $strategyName")
        }
    }

    private fun validateNoExtraFields(
        obj: JSONObject,
        prohibited: List<String>,
        strategyName: String,
        category: String = "additional"
    ) {
        val found = prohibited.filter { obj.has(it) }
        if (found.isNotEmpty()) {
            throw IllegalArgumentException(
                "$strategyName must not have $category fields: ${found.joinToString(", ")}"
            )
        }
    }

    private fun parseConfig(
        json: String,
        modelPath: String,
        language: String
    ): SttConfig {
        val root = JSONObject(json)

        // ── ttsEngineConfig block ─────────────────────────────────────────
        val engineObj = root.getJSONObject("ttsEngineConfig")
        val debugLoggingEnabled = engineObj.optBoolean("debugLoggingEnabled", false)

        // ── vadConfig block ───────────────────────────────────────────────
        val vadObj = root.getJSONObject("vadConfig")
        val energyThreshold = vadObj.getDouble("energyThreshold").toFloat()
        val preRollMs = vadObj.getInt("preRollMs")
        val stableChunkSizeMs = vadObj.getInt("stableChunkSizeMs")

        // ── drainMode ─────────────────────────────────────────────────────
        val drainModeString = root.getString("drainMode")
        val drainMode = try {
            DrainMode.valueOf(drainModeString)
        } catch (_: IllegalArgumentException) {
            throw IllegalStateException(
                "Invalid drainMode='$drainModeString'. Allowed: DRAIN_FROM_NEXT_FRAME, DRAIN_FROM_HEAD."
            )
        }

        // ── startStrategy block (validated) ───────────────────────────────
        val startObj = root.getJSONObject("startStrategy")
        val startType = startObj.getString("type")
        val startTrigger: StartTrigger = parseStartTrigger(startObj, startType)

        // ── stopStrategy block (validated) ───────────────────────────────
        val stopObj = root.getJSONObject("stopStrategy")
        val stopType = stopObj.getString("type")
        val stopTrigger: StopTrigger = parseStopTrigger(stopObj, stopType)

        // ── warmup block (optional) ─────────────────────────────────────
        val warmupEnabled = root.optBoolean("warmupEnabled", false)
        val warmupDurationMs = root.optInt("warmupDurationMs", 0)

        return SttConfig(
            modelPath = modelPath,
            language = language,
            debugLoggingEnabled = debugLoggingEnabled,
            energyThreshold = energyThreshold,
            preRollMs = preRollMs,
            stableChunkSizeMs = stableChunkSizeMs,
            drainMode = drainMode,
            startTrigger = startTrigger,
            stopTrigger = stopTrigger,
            warmupEnabled = warmupEnabled,
            warmupDurationMs = warmupDurationMs
        )
    }
}
