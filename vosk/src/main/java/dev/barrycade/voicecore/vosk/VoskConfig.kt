package dev.barrycade.voicecore.vosk

/**
 * Configuration for a Vosk recognition session.
 *
 * All fields have safe defaults. Use the DSL-style factory or
 * load from JSON via [VoskJsonAdapter.parseConfig].
 *
 * ## Field naming alignment
 *
 * Fields use names consistent with the Whisper STT config:
 * - [preSpeechPadMs] ↔ Whisper's [preRollMs]
 * - [postSpeechSilenceMs] ↔ Whisper's [autoSilenceMs]
 *
 * Note: Vosk uses **float seconds** for endpointer delays,
 * while Whisper uses **int milliseconds**. Conversion is
 * handled by [VoskJsonAdapter].
 *
 * @property modelPath Absolute path to the Vosk model directory.
 * @property sampleRate Audio sample rate in Hz.
 * @property endpointerMode Vosk endpointer mode: "SHORT" or "LONG".
 * @property postSpeechSilenceMs Silence after speech before utterance ends (seconds).
 * @property preSpeechPadMs Silence before speech to begin utterance (seconds).
 * @property maxDurationMs Maximum utterance duration (seconds).
 * @property wakeWord The wake word to listen for in wake-word mode.
 * @property bufferSizeSamples Number of short samples per audio read chunk.
 */
data class VoskConfig(
    val modelPath: String,
    val sampleRate: Float = 16000f,
    val endpointerMode: String = "SHORT",
    val postSpeechSilenceMs: Float = 1.2f,
    val preSpeechPadMs: Float = 0.5f,
    val maxDurationMs: Float = 30.0f,
    val wakeWord: String = "Max",
    val bufferSizeSamples: Int = 4000
) {
    init {
        require(modelPath.isNotBlank()) {
            "modelPath must not be blank"
        }
        require(sampleRate in 8000f..48000f) {
            "sampleRate=$sampleRate must be in [8000, 48000] Hz"
        }
        require(endpointerMode == "SHORT" || endpointerMode == "LONG") {
            "endpointerMode must be 'SHORT' or 'LONG', got '$endpointerMode'"
        }
        require(postSpeechSilenceMs in 0.1f..60.0f) {
            "postSpeechSilenceMs=$postSpeechSilenceMs must be in [0.1, 60.0] seconds"
        }
        require(preSpeechPadMs in 0.0f..10.0f) {
            "preSpeechPadMs=$preSpeechPadMs must be in [0.0, 10.0] seconds"
        }
        require(maxDurationMs in 1.0f..120.0f) {
            "maxDurationMs=$maxDurationMs must be in [1.0, 120.0] seconds"
        }
        require(bufferSizeSamples in 1024..16000) {
            "bufferSizeSamples=$bufferSizeSamples must be in [1024, 16000]"
        }
    }
}
