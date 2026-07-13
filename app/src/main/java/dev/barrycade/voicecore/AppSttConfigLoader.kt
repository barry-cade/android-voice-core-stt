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
        val startTrigger: StartTrigger = when (startType) {
            "MANUAL" -> {
                if (startObj.has("vadStartThreshold") || startObj.has("minSpeechMs") ||
                    startObj.has("wakeWord") || startObj.has("confidenceThreshold")
                ) {
                    val extra = mutableListOf<String>()
                    if (startObj.has("vadStartThreshold")) extra.add("vadStartThreshold")
                    if (startObj.has("minSpeechMs")) extra.add("minSpeechMs")
                    if (startObj.has("wakeWord")) extra.add("wakeWord")
                    if (startObj.has("confidenceThreshold")) extra.add("confidenceThreshold")
                    throw IllegalArgumentException(
                        "MANUAL startStrategy must not have additional fields: ${extra.joinToString(", ")}"
                    )
                }
                StartTrigger.Manual
            }
            "VAD_START" -> {
                if (!startObj.has("vadStartThreshold")) {
                    throw IllegalArgumentException(
                        "vadStartThreshold is required for VAD_START startStrategy"
                    )
                }
                if (startObj.has("wakeWord") || startObj.has("confidenceThreshold")) {
                    val extra = mutableListOf<String>()
                    if (startObj.has("wakeWord")) extra.add("wakeWord")
                    if (startObj.has("confidenceThreshold")) extra.add("confidenceThreshold")
                    throw IllegalArgumentException(
                        "VAD_START startStrategy must not have wake-word fields: ${extra.joinToString(", ")}"
                    )
                }
                if (!startObj.has("minSpeechMs")) {
                    throw IllegalArgumentException(
                        "minSpeechMs is required for VAD_START startStrategy"
                    )
                }
                StartTrigger.VadStart(
                    vadStartThreshold = startObj.getDouble("vadStartThreshold").toFloat(),
                    minSpeechMs = startObj.getInt("minSpeechMs")
                )
            }
            "WAKEWORD" -> {
                if (!startObj.has("wakeWord")) {
                    throw IllegalArgumentException(
                        "wakeWord is required for WAKEWORD startStrategy"
                    )
                }
                if (!startObj.has("confidenceThreshold")) {
                    throw IllegalArgumentException(
                        "confidenceThreshold is required for WAKEWORD startStrategy"
                    )
                }
                if (startObj.has("vadStartThreshold") || startObj.has("minSpeechMs")) {
                    val extra = mutableListOf<String>()
                    if (startObj.has("vadStartThreshold")) extra.add("vadStartThreshold")
                    if (startObj.has("minSpeechMs")) extra.add("minSpeechMs")
                    throw IllegalArgumentException(
                        "WAKEWORD startStrategy must not have VAD fields: ${extra.joinToString(", ")}"
                    )
                }
                StartTrigger.WakeWordStart(
                    wakeWord = startObj.getString("wakeWord"),
                    confidenceThreshold = startObj.getDouble("confidenceThreshold").toFloat()
                )
            }
            else -> throw IllegalArgumentException("Unknown startStrategy type: $startType")
        }

        // ── stopStrategy block (validated) ───────────────────────────────
        val stopObj = root.getJSONObject("stopStrategy")
        val stopType = stopObj.getString("type")
        val stopTrigger: StopTrigger = when (stopType) {
            "MANUAL" -> {
                if (stopObj.has("silenceMs") || stopObj.has("maxDurationMs")) {
                    val extra = mutableListOf<String>()
                    if (stopObj.has("silenceMs")) extra.add("silenceMs")
                    if (stopObj.has("maxDurationMs")) extra.add("maxDurationMs")
                    throw IllegalArgumentException(
                        "MANUAL stopStrategy must not have additional fields: ${extra.joinToString(", ")}"
                    )
                }
                StopTrigger.Manual
            }
            "AUTO_SILENCE" -> {
                if (!stopObj.has("silenceMs")) {
                    throw IllegalArgumentException(
                        "silenceMs is required for AUTO_SILENCE stopStrategy"
                    )
                }
                if (!stopObj.has("maxDurationMs")) {
                    throw IllegalArgumentException(
                        "maxDurationMs is required for AUTO_SILENCE stopStrategy"
                    )
                }
                StopTrigger.AutoSilence(
                    silenceMs = stopObj.getInt("silenceMs"),
                    maxDurationMs = stopObj.getInt("maxDurationMs")
                )
            }
            "DURATION" -> {
                if (stopObj.has("silenceMs")) {
                    throw IllegalArgumentException(
                        "DURATION stopStrategy must not have silenceMs field"
                    )
                }
                if (!stopObj.has("maxDurationMs")) {
                    throw IllegalArgumentException(
                        "maxDurationMs is required for DURATION stopStrategy"
                    )
                }
                StopTrigger.Duration(
                    maxDurationMs = stopObj.getInt("maxDurationMs")
                )
            }
            else -> throw IllegalArgumentException("Unknown stopStrategy type: $stopType")
        }

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
