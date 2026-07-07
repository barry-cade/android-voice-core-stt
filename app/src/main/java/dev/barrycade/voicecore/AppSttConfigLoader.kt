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
        val motionMode = root.getJSONObject("motionMode")
        return AppRuntimeSttConfig(
            energyThreshold = root.getDouble("energyThreshold").toFloat(),
            silencePaddingMs = root.getInt("silencePaddingMs"),
            preRollMs = root.getInt("preRollMs"),
            maxUtteranceLengthMs = root.getInt("maxUtteranceLengthMs"),
            stableChunkSizeMs = root.getInt("stableChunkSizeMs"),
            motionMode = AppMotionModeConfig(
                energyThreshold = motionMode.getDouble("energyThreshold").toFloat(),
                silencePaddingMs = motionMode.getInt("silencePaddingMs")
            ),
            startStrategy = root.optString("startStrategy", "manual"),
            stopStrategy = root.optString("stopStrategy", "manual")
        )
    }
}
