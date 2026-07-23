package dev.barrycade.voicecore.vosk

/**
 * Configuration for a Vosk recognition session.
 *
 * All fields have safe defaults. Use the DSL-style factory or
 * load from JSON via [VoskJsonAdapter.parseConfig].
 *
 * ## Endpointer parameters
 *
 * Vosk's [org.vosk.Recognizer.setEndpointerDelays] takes **three** parameters
 * (vosk-android 0.3.75 API). The 5 values logged by Vosk are internally
 * derived from these 3 inputs:
 *
 * | Field | setEndpointerDelays param | Meaning | Logged as |
 * | --- | --- | --- | --- |
 * | [preSpeechStartMaxMs] | t_start_max (1st) | Max time to wait for speech at start | rule1 |
 * | [postSpeechSilenceMs] | t_end (2nd) | Trailing silence after speech before endpoint | rule2 (rule3=1.5x, rule4=2x) |
 * | [maxUtteranceMs] | t_max (3rd) | Max utterance length; also used internally for min utterance length | rule5 |
 *
 * All time fields are in **milliseconds**. Conversion to Vosk's float-seconds
 * for the endpointer API is handled by [VoskEngine].
 *
 * @property modelPath Absolute path to the Vosk model directory.
 * @property sampleRate Audio sample rate in Hz.
 * @property endpointerMode Vosk endpointer mode: "SHORT" or "LONG".
 * @property preSpeechStartMaxMs Max time to wait for speech at start (milliseconds). Maps to setEndpointerDelays 1st param (t_start_max).
 * @property postSpeechSilenceMs Trailing silence after speech before endpoint (milliseconds). Maps to setEndpointerDelays 2nd param (t_end).
 * @property maxUtteranceMs Max utterance length (milliseconds). Maps to setEndpointerDelays 3rd param (t_max).
 * @property wakeWord The wake word to listen for in wake-word mode.
 * @property bufferSizeSamples Number of short samples per audio read chunk.
 */
data class VoskConfig(
    val modelPath: String,
    val sampleRate: Float = 16000f,
    val endpointerMode: String = "SHORT",
    val preSpeechStartMaxMs: Float = 500f,
    val postSpeechSilenceMs: Float = 1200f,
    val maxUtteranceMs: Float = 30000f,
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
        require(preSpeechStartMaxMs >= 0f) {
            "preSpeechStartMaxMs=$preSpeechStartMaxMs must be >= 0"
        }
        require(postSpeechSilenceMs >= 0f) {
            "postSpeechSilenceMs=$postSpeechSilenceMs must be >= 0"
        }
        require(maxUtteranceMs >= 0f && maxUtteranceMs <= 60000f) {
            "maxUtteranceMs=$maxUtteranceMs must be in [0, 60000] ms"
        }
        require(maxUtteranceMs >= postSpeechSilenceMs) {
            "maxUtteranceMs=$maxUtteranceMs must be >= postSpeechSilenceMs=$postSpeechSilenceMs"
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
         *   "preSpeechStartMaxMs": 500,
         *   "postSpeechSilenceMs": 1200,
         *   "maxUtteranceMs": 30000,
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

