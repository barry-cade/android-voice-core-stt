package dev.barrycade.voicecore

// TODO(major-version): Remove legacy config path after full migration to SttRunConfig.

import android.content.Context
import android.util.Log
import dev.barrycade.voicecore.stt.ManualAutoSpecific
import dev.barrycade.voicecore.stt.ManualManualSpecific
import dev.barrycade.voicecore.stt.SttLifeCycleStrategy
import dev.barrycade.voicecore.stt.SttRunConfig
import dev.barrycade.voicecore.stt.TtsEngineConfig
import org.json.JSONObject

object AppSttConfigLoader {
    private const val TAG = "STT_CONFIG"

    // ── Existing loader (unchanged) ────────────────────────────────────────

    fun loadFromAssets(context: Context): AppRuntimeSttConfig {
        val inputStream = context.assets.open("stt_config.json")
        val json = inputStream.use { it.readBytes().toString(Charsets.UTF_8) }

        return try {
            parse(json)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid STT configuration", e)
            throw IllegalStateException("Invalid STT configuration: ${e.message}", e)
        }
    }

    private fun parse(json: String): AppRuntimeSttConfig {
        val root = JSONObject(json)

        // Mode-specific blocks (optional — defaults apply if missing)
        val manualManualObj = root.optJSONObject("manualManual")
        val manualAutoObj = root.optJSONObject("manualAuto")
        val reasonMessagesObj = root.optJSONObject("reasonMessages")

        return AppRuntimeSttConfig(
            energyThreshold = root.getDouble("energyThreshold").toFloat(),
            preRollMs = root.getInt("preRollMs"),
            stableChunkSizeMs = root.getInt("stableChunkSizeMs"),
            manualManual = if (manualManualObj != null) {
                AppManualManualConfig(
                    maxDurationMs = manualManualObj.optInt("maxDurationMs", 30000),
                    abnormalSilenceMs = manualManualObj.optInt("abnormalSilenceMs", 5000)
                )
            } else {
                AppManualManualConfig()
            },
            manualAuto = if (manualAutoObj != null) {
                AppManualAutoConfig(
                    maxDurationMs = manualAutoObj.optInt("maxDurationMs", 30000),
                    autoSilenceMs = manualAutoObj.optInt("autoSilenceMs", 1200)
                )
            } else {
                AppManualAutoConfig()
            },
            reasonMessages = if (reasonMessagesObj != null) {
                AppReasonMessages(
                    tooLong = reasonMessagesObj.optString("tooLong", "You spoke for too long."),
                    abnormalSilence = reasonMessagesObj.optString("abnormalSilence", "You stopped speaking for too long.")
                )
            } else {
                AppReasonMessages()
            },
            startStrategy = root.optString("startStrategy", "manual"),
            stopStrategy = root.optString("stopStrategy", "manual")
        )
    }

    // ── New loader for SttRunConfig (Phase 3) ─────────────────────────────

    /**
     * Load and construct a fully validated [SttRunConfig] from the existing
     * [stt_config.json] asset.
     *
     * Maps the JSON fields as follows:
     * - `energyThreshold`, `preRollMs`, `stableChunkSizeMs`, `debugLoggingEnabled`
     *   → [TtsEngineConfig] fields
     * - `startStrategy` + `stopStrategy` → [SttLifeCycleStrategy]
     * - `manualManual` or `manualAuto` block → [ManualManualSpecific] or
     *   [ManualAutoSpecific]
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
     * - start="manual" + stop="manual" → [SttLifeCycleStrategy.MANUAL_MANUAL]
     * - start="manual" + stop="autoSilence" → [SttLifeCycleStrategy.MANUAL_AUTO]
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
