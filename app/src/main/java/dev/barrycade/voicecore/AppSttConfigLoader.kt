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
 * Loads [SttRunConfig] from the [stt_config.json] asset.
 *
 * This is the sole config loader for the app — the legacy [SttConfig] path
 * has been removed.
 */
object AppSttConfigLoader {
    private const val TAG = "STT_CONFIG"

    /**
     * Load and construct a fully validated [SttRunConfig] from [stt_config.json].
     *
     * @param context Android context for asset access.
     * @param modelPath Absolute file path to the Whisper model binary.
     * @param language Language code for transcription (e.g. "en").
     * @return A fully constructed [SttRunConfig].
     * @throws IllegalStateException if the JSON is missing required fields.
     */
    fun loadSttRunConfig(
        context: Context,
        modelPath: String,
        language: String
    ): SttRunConfig {
        val inputStream = context.assets.open("stt_config.json")
        val json = inputStream.use { it.readBytes().toString(Charsets.UTF_8) }

        return try {
            parseSttRunConfig(json, modelPath, language)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid STT configuration for SttRunConfig", e)
            throw IllegalStateException("Invalid STT configuration: ${e.message}", e)
        }
    }

    /**
     * Parse JSON string into a [SttRunConfig].
     *
     * Uses `get*` (not `opt*`) for required fields so missing values throw
     * a [JSONException] immediately — fail fast, no defaults, no inference.
     */
    private fun parseSttRunConfig(
        json: String,
        modelPath: String,
        language: String
    ): SttRunConfig {
        val root = JSONObject(json)

        // ── Required engine fields (fail fast if missing) ─────────────────
        val energyThreshold = root.getDouble("energyThreshold").toFloat()
        val preRollMs = root.getInt("preRollMs")
        val stableChunkSizeMs = root.getInt("stableChunkSizeMs")
        val debugLoggingEnabled = root.optBoolean("debugLoggingEnabled", false)

        // ── Build TtsEngineConfig ─────────────────────────────────────────
        val engineConfig = TtsEngineConfig(
            modelPath = modelPath,
            language = language,
            preRollMs = preRollMs,
            stableChunkSizeMs = stableChunkSizeMs,
            debugLoggingEnabled = debugLoggingEnabled
        )

        // ── Determine lifecycle strategy from JSON strings ────────────────
        val startStrategy = root.getString("startStrategy")
        val stopStrategy = root.getString("stopStrategy")

        val lifecycleStrategy = resolveLifecycleStrategy(startStrategy, stopStrategy)

        // ── Build strategy-specific config ────────────────────────────────
        val strategySpecific: Any = when (lifecycleStrategy) {
            SttLifeCycleStrategy.MANUAL_MANUAL -> {
                val mmObj = root.optJSONObject("manualManual")
                if (mmObj == null) {
                    throw IllegalStateException(
                        "Missing 'manualManual' block in config for MANUAL_MANUAL strategy"
                    )
                }
                ManualManualSpecific(
                    energyThreshold = energyThreshold,
                    maxDurationMs = mmObj.getInt("maxDurationMs"),
                    abnormalSilenceMs = mmObj.getInt("abnormalSilenceMs")
                )
            }
            SttLifeCycleStrategy.MANUAL_AUTO -> {
                val maObj = root.optJSONObject("manualAuto")
                if (maObj == null) {
                    throw IllegalStateException(
                        "Missing 'manualAuto' block in config for MANUAL_AUTO strategy"
                    )
                }
                ManualAutoSpecific(
                    energyThreshold = energyThreshold,
                    maxDurationMs = maObj.getInt("maxDurationMs"),
                    autoSilenceMs = maObj.getInt("autoSilenceMs")
                )
            }
        }

        return SttRunConfig(
            ttsEngineConfig = engineConfig,
            ttsLifeCycleStrategy = lifecycleStrategy,
            strategySpecific = strategySpecific
        )
    }

    /**
     * Resolve [SttLifeCycleStrategy] from the JSON strategy strings.
     *
     * Allowed combinations:
     * - start="manual" + stop="manual" -> [SttLifeCycleStrategy.MANUAL_MANUAL]
     * - start="manual" + stop="autoSilence" -> [SttLifeCycleStrategy.MANUAL_AUTO]
     *
     * @throws IllegalStateException for unrecognised combinations.
     */
    private fun resolveLifecycleStrategy(
        startStrategy: String,
        stopStrategy: String
    ): SttLifeCycleStrategy {
        val start = startStrategy.lowercase()
        val stop = stopStrategy.lowercase()

        if (start != "manual") {
            throw IllegalStateException(
                "Unsupported startStrategy='$startStrategy'. Only 'manual' is supported."
            )
        }

        return when (stop) {
            "manual" -> SttLifeCycleStrategy.MANUAL_MANUAL
            "autosilence" -> SttLifeCycleStrategy.MANUAL_AUTO
            else -> throw IllegalStateException(
                "Unsupported stopStrategy='$stopStrategy'. " +
                    "Allowed: manual, autoSilence."
            )
        }
    }
}
