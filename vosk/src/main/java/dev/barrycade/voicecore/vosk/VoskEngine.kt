package dev.barrycade.voicecore.vosk

class VoskEngine(
    private val modelPath: String
) {
    fun load() {
        // TODO: Load Vosk model here
    }

    fun transcribe(pcm: ShortArray): String {
        // TODO: Run Vosk recognizer here
        return ""
    }

    fun close() {
        // TODO: Release Vosk resources here
    }
}
