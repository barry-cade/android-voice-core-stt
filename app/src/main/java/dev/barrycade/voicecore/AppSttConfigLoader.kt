package dev.barrycade.voicecore

import android.content.Context
import android.util.Log
import org.json.JSONObject

object AppSttConfigLoader {
    private const val TAG = "STT_CONFIG"

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
}
