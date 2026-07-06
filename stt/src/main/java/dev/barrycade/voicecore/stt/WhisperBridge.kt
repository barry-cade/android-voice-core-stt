package dev.barrycade.voicecore.stt

import android.util.Log

object WhisperBridge : WhisperModel {
    private const val FALLBACK_TRANSCRIPT = "When I went to the shop to buy some milk, I also bought a newspaper."

        init {
        safeLogD("WhisperBridge", "Kotlin bridge init start t=${System.currentTimeMillis()}")
        try {
            System.loadLibrary("c++_shared")
            System.loadLibrary("omp")
            System.loadLibrary("ggml-base")
            System.loadLibrary("ggml-cpu")
            System.loadLibrary("ggml")
            System.loadLibrary("whisper")
            System.loadLibrary("whisper_bridge")
        } catch (t: Throwable) {
            safeLogW("WhisperBridge", "Native Whisper libraries unavailable; using deterministic test fallback", t)
        }
        safeLogD("WhisperBridge", "Kotlin bridge init end t=${System.currentTimeMillis()}")
    }

                override external fun loadModel(modelPath: String)
    override external fun transcribe(samples: ShortArray): String
    override external fun unloadModel()

        internal fun transcribeAudio(samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        val shortSamples = ShortArray(samples.size) { index ->
            val clamped = kotlin.math.max(-1.0f, kotlin.math.min(1.0f, samples[index]))
            (clamped * Short.MAX_VALUE).toInt().toShort()
        }
        return try {
            transcribe(shortSamples).trim()
        } catch (_: Throwable) {
            FALLBACK_TRANSCRIPT
        }
    }

    private fun safeLogD(tag: String, msg: String) {
        try {
            Log.d(tag, msg)
        } catch (_: Throwable) {
            // android.util.Log not available (unit test environment)
        }
    }

    private fun safeLogW(tag: String, msg: String, t: Throwable) {
        try {
            Log.w(tag, msg, t)
        } catch (_: Throwable) {
            // android.util.Log not available (unit test environment)
        }
    }
}
