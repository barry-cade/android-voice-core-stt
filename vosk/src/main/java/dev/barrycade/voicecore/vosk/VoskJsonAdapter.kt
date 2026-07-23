package dev.barrycade.voicecore.vosk

/**
 * JSON adapter that parses Vosk configuration strings into [VoskConfig] instances.
 *
 * Uses manual regex-based JSON parsing (no org.json.JSONObject dependency)
 * to avoid mocking issues in the Android unit test environment.
 *
 * ## Expected JSON shape (flat caller-friendly format)
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
 * All time values are in milliseconds. Conversion to float seconds
 * for Vosk's endpointer API is handled by [VoskEngine].
 *
 * All fields are optional except [modelPath]. Missing optional fields
 * fall back to defaults defined in [VoskConfig].
 *
 * @see VoskConfig
 */
internal object VoskJsonAdapter {

    /**
     * Parse a JSON config string into a [VoskConfig].
     *
     * @param json Raw JSON string.
     * @return Parsed [VoskConfig].
     * @throws IllegalArgumentException if required fields are missing or invalid.
     */
    fun parseConfig(json: String): VoskConfig {
        val modelPath = resolveString(json, "modelPath")
            ?: throw IllegalArgumentException("Missing required field: modelPath")

        val sampleRate = resolveFloat(json, "sampleRate") ?: 16000f
        val endpointerMode = resolveString(json, "endpointerMode") ?: "SHORT"
        val postSpeechSilenceMs = resolveFloat(json, "postSpeechSilenceMs") ?: 1.2f
        val preSpeechPadMs = resolveFloat(json, "preSpeechPadMs") ?: 0.5f
        val maxDurationMs = resolveFloat(json, "maxDurationMs") ?: 30.0f
        val wakeWord = resolveString(json, "wakeWord") ?: "Max"
        val bufferSizeSamples = resolveInt(json, "bufferSizeSamples") ?: 4000

        return VoskConfig(
            modelPath = modelPath,
            sampleRate = sampleRate,
            endpointerMode = endpointerMode,
            postSpeechSilenceMs = postSpeechSilenceMs,
            preSpeechPadMs = preSpeechPadMs,
            maxDurationMs = maxDurationMs,
            wakeWord = wakeWord,
            bufferSizeSamples = bufferSizeSamples
        )
    }

    /**
     * Serialize a [VoskConfig] back to a JSON string.
     *
     * Output shape matches the flat format consumed by [parseConfig].
     */
    fun buildConfigJson(config: VoskConfig): String {
        val sb = StringBuilder()
        sb.append('{')
        appendJsonString(sb, "modelPath", config.modelPath)
        sb.append(',')
        appendJsonFloat(sb, "sampleRate", config.sampleRate)
        sb.append(',')
        appendJsonString(sb, "endpointerMode", config.endpointerMode)
        sb.append(',')
        appendJsonFloat(sb, "postSpeechSilenceMs", config.postSpeechSilenceMs)
        sb.append(',')
        appendJsonFloat(sb, "preSpeechPadMs", config.preSpeechPadMs)
        sb.append(',')
        appendJsonFloat(sb, "maxDurationMs", config.maxDurationMs)
        sb.append(',')
        appendJsonString(sb, "wakeWord", config.wakeWord)
        sb.append(',')
        appendJsonInt(sb, "bufferSizeSamples", config.bufferSizeSamples)
        sb.append('}')
        return sb.toString()
    }

    // ── JSON pretty-printing helpers ──────────────────────────────────────

    /**
     * Format a JSON string for human-readable display.
     *
     * Intended for debug/config-display use only (not round-trip stable).
     *
     * @param json Compact JSON string.
     * @return Pretty-printed JSON string with 2-space indentation.
     */
    fun prettyPrint(json: String): String {
        val sb = StringBuilder()
        var indent = 0
        var inString = false
        var i = 0

        while (i < json.length) {
            val c = json[i]
            when {
                c == '"' && !inString -> {
                    inString = true
                    sb.append(c)
                }
                c == '"' && inString -> {
                    inString = false
                    sb.append(c)
                }
                inString -> {
                    sb.append(c)
                    if (c == '\\' && i + 1 < json.length) {
                        i++
                        sb.append(json[i])
                    }
                }
                c == '{' || c == '[' -> {
                    sb.append(c)
                    sb.append('\n')
                    indent += 2
                    sb.append(" ".repeat(indent))
                }
                c == '}' || c == ']' -> {
                    sb.append('\n')
                    indent -= 2
                    sb.append(" ".repeat(indent))
                    sb.append(c)
                }
                c == ',' -> {
                    sb.append(c)
                    sb.append('\n')
                    sb.append(" ".repeat(indent))
                }
                c == ':' -> {
                    sb.append(": ")
                }
                !c.isWhitespace() -> {
                    sb.append(c)
                }
            }
            i++
        }
        return sb.toString()
    }

    // ── JSON serialisation helpers ────────────────────────────────────────

    private fun appendJsonString(sb: StringBuilder, key: String, value: String) {
        sb.append('"')
        sb.append(key)
        sb.append("\":\"")
        sb.append(escapeJson(value))
        sb.append('"')
    }

    private fun appendJsonInt(sb: StringBuilder, key: String, value: Int) {
        sb.append('"')
        sb.append(key)
        sb.append("\":")
        sb.append(value)
    }

    private fun appendJsonFloat(sb: StringBuilder, key: String, value: Float) {
        sb.append('"')
        sb.append(key)
        sb.append("\":")
        // Remove trailing zeros for cleaner output.
        val formatted = value.toDouble().toString()
        sb.append(formatted)
    }

    private fun escapeJson(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    // ── Manual JSON value extraction helpers ──────────────────────────────

    /**
     * Extract a string value for [key] from the top-level JSON object.
     */
    private fun resolveString(json: String, key: String): String? {
        val regex = Regex(""""$key"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        return regex.find(json)?.groupValues?.get(1)
    }

    /**
     * Extract a float value for [key] from the top-level JSON object.
     */
    private fun resolveFloat(json: String, key: String): Float? {
        val regex = Regex(""""$key"\s*:\s*(-?\d+(?:\.\d+)?)(?:[,\s}]|$)""")
        return regex.find(json)?.groupValues?.get(1)?.toFloatOrNull()
    }

    /**
     * Extract an integer value for [key] from the top-level JSON object.
     */
    private fun resolveInt(json: String, key: String): Int? {
        val regex = Regex(""""$key"\s*:\s*(-?\d+)(?:[,\s}]|$)""")
        return regex.find(json)?.groupValues?.get(1)?.toIntOrNull()
    }
}
