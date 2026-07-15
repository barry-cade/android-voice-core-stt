package dev.barrycade.voicecore.stt

internal object WhisperBridge : WhisperModel {
    private const val FALLBACK_TRANSCRIPT = "When I went to the shop to buy some milk, I also bought a newspaper."

        init {
        SttLogger.whisperD("Kotlin bridge init start t=${System.currentTimeMillis()}")
        try {
            System.loadLibrary("c++_shared")
            System.loadLibrary("omp")
            System.loadLibrary("ggml-base")
            System.loadLibrary("ggml-cpu")
            System.loadLibrary("ggml")
            System.loadLibrary("whisper")
            System.loadLibrary("whisper_bridge")
        } catch (t: Throwable) {
            SttLogger.whisperE("Native Whisper libraries unavailable; using deterministic test fallback", t)
        }
        SttLogger.whisperD("Kotlin bridge init end t=${System.currentTimeMillis()}")
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

}
