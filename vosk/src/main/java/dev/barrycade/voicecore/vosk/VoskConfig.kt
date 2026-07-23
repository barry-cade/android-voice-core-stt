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
 * All time fields are in **milliseconds** (Int or Float), matching
 * the Whisper naming convention. Conversion to Vosk's float-seconds
 * for the endpointer API is handled by [VoskEngine].
 *
 * @property modelPath Absolute path to the Vosk model directory.
 * @property sampleRate Audio sample rate in Hz.
 * @property endpointerMode Vosk endpointer mode: "SHORT" or "LONG".
 * @property postSpeechSilenceMs Silence after speech before utterance ends (milliseconds).
 * @property preSpeechPadMs Silence before speech to begin utterance (milliseconds).
 * @property maxDurationMs Maximum utterance duration (milliseconds).
 * @property wakeWord The wake word to listen for in wake-word mode.
 * @property bufferSizeSamples Number of short samples per audio read chunk.
 */
data class VoskConfig(
    val modelPath: String,
    val sampleRate: Float = 16000f,
    val endpointerMode: String = "SHORT",
    val postSpeechSilenceMs: Float = 1200f,
    val preSpeechPadMs: Float = 500f,
    val maxDurationMs: Float = 30000f,
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
        require(postSpeechSilenceMs in 100f..60000f) {
            "postSpeechSilenceMs=$postSpeechSilenceMs must be in [100, 60000] ms"
        }
        require(preSpeechPadMs in 0f..10000f) {
            "preSpeechPadMs=$preSpeechPadMs must be in [0, 10000] ms"
        }
        require(maxDurationMs in 1000f..120000f) {
            "maxDurationMs=$maxDurationMs must be in [1000, 120000] ms"
        }
        require(bufferSizeSamples in 1024..16000) {
            "bufferSizeSamples=$bufferSizeSamples must be in [1024, 16000]"
        }
    }

    companion object {
        /**
         * Parse a JSON config string into a [VoskConfig].
         *
         * The JSON format is flat and caller-friendly:
         * ```json
         * {
         *   "modelPath": "/path/to/vosk/model",
         *   "sampleRate": 16000,
         *   "endpointerMode": "SHORT",
         *   "postSpeechSilenceMs": 1200,
         *   "preSpeechPadMs": 500,
         *   "maxDurationMs": 30000,
         *   "wakeWord": "Max",
         *   "bufferSizeSamples": 4000
         * }
         * ```
         *
         * All fields except [modelPath] are optional and fall back to defaults.
         *
         * @param json Raw JSON string.
         * @return Parsed [VoskConfig].
         * @throws IllegalArgumentException if required fields are missing or invalid.
         */
        fun fromJson(json: String): VoskConfig {
            return VoskJsonAdapter.parseConfig(json)
        }
    }
}
