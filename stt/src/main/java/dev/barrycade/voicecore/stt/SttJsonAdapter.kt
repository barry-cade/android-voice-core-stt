package dev.barrycade.voicecore.stt

/**
 * Internal JSON adapter that serialises and deserialises data crossing
 * the STT/application boundary.
 *
 * ## Responsibilities
 *
 * - Parse incoming JSON config strings into internal [SttConfig] instances.
 * - Serialise internal results/errors/timing into outgoing JSON message strings.
 *
 * ## Boundary principle
 *
 * This adapter is the ONLY code that translates between JSON and internal types.
 * No other component in the STT module reads or writes JSON.
 * No internal type ever leaks into a JSON string (except through this class).
 *
 * @see [PLAN_STT_JSON_BOUNDARY.md] for the JSON schema definitions.
 */
internal object SttJsonAdapter {

    // ═════════════════════════════════════════════════════════════════════
    // Input: JSON → SttConfig
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Parse a JSON config string into an [SttConfig].
     *
     * Expected JSON shape — flat caller-friendly format:
     * ```json
     * {
     *   "modelPath": "/path/to/model.bin",
     *   "language": "en",
     *   "debugLoggingEnabled": true,
     *   "energyThreshold": 0.03,
     *   "preRollMs": 100,
     *   "stableChunkSizeMs": 500,
     *   "drainMode": "DRAIN_FROM_NEXT_FRAME",
     *   "startType": "MANUAL",
     *   "stopType": "AUTO_SILENCE",
     *   "silenceMs": 1200,
     *   "maxDurationMs": 30000,
     *   "warmupEnabled": true,
     *   "warmupDurationMs": 3000,
     *   "highPassCutoffHz": 0,
     *   "zcrEnabled": false,
     *   "sttMode": "ALWAYS_ON",
     *   "grammar": null,
     *   "partialsEnabled": false,
     *   "autoReturn": false
     * }
     * ```
     *
     * Also supports the legacy nested format:
     * ```json
     * {
     *   "ttsEngineConfig": { "modelPath": "...", "language": "en", "debugLoggingEnabled": true },
     *   "vadConfig": { "energyThreshold": 0.03, "preRollMs": 100, "stableChunkSizeMs": 500 },
     *   "drainMode": "DRAIN_FROM_NEXT_FRAME",
     *   "startStrategy": { "type": "MANUAL" },
     *   "stopStrategy": { "type": "AUTO_SILENCE", "silenceMs": 1200, "maxDurationMs": 30000 },
     *   "warmupEnabled": true,
     *   "warmupDurationMs": 3000
     * }
     * ```
     *
     * Uses manual JSON parsing (no org.json.JSONObject dependency) to avoid
     * mocking issues in the Android unit test environment.
     *
     * @throws IllegalArgumentException if required fields are missing or invalid.
     */
    fun parseConfig(json: String): SttConfig {
        // ── Model path & language ────────────────────────────────────────
        val modelPath = resolveString(json, "modelPath")
            ?: resolveNestedEngineString(json, "modelPath")
            ?: throw IllegalArgumentException("Missing required field: modelPath")
        val language = resolveString(json, "language")
            ?: resolveNestedEngineString(json, "language") ?: "en"
        val debugLoggingEnabled = resolveBoolean(json, "debugLoggingEnabled")
            ?: resolveNestedEngineBoolean(json, "debugLoggingEnabled") ?: false

        // ── VAD config ───────────────────────────────────────────────────
        val energyThreshold = resolveDouble(json, "energyThreshold")
            ?: resolveNestedVadDouble(json, "energyThreshold")
            ?: throw IllegalArgumentException("Missing required field: energyThreshold")
        val preRollMs = resolveInt(json, "preRollMs")
            ?: resolveNestedVadInt(json, "preRollMs")
            ?: throw IllegalArgumentException("Missing required field: preRollMs")
        val stableChunkSizeMs = resolveInt(json, "stableChunkSizeMs")
            ?: resolveNestedVadInt(json, "stableChunkSizeMs")
            ?: throw IllegalArgumentException("Missing required field: stableChunkSizeMs")

        // ── Drain mode ───────────────────────────────────────────────────
        val drainModeString = resolveString(json, "drainMode")
            ?: throw IllegalArgumentException("Missing required field: drainMode")
        val drainMode = try {
            DrainMode.valueOf(drainModeString)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException(
                "Invalid drainMode='$drainModeString'. Allowed: DRAIN_FROM_NEXT_FRAME, DRAIN_FROM_HEAD."
            )
        }

        // ── Start strategy ───────────────────────────────────────────────
        val startTrigger = parseStartTrigger(json)

        // ── Stop strategy ────────────────────────────────────────────────
        val stopTrigger = parseStopTrigger(json)

        // ── Warmup (optional) ────────────────────────────────────────────
        val warmupEnabled = resolveBoolean(json, "warmupEnabled") ?: false
        val warmupDurationMs = resolveInt(json, "warmupDurationMs") ?: 0

        // ── Session timeout (optional, 0 = no timeout) ───────────────────
        val sessionTimeoutMs = resolveInt(json, "sessionTimeoutMs") ?: 0

        // ── Buffer size (optional) ───────────────────────────────────────
        val bufferSizeSamples = resolveInt(json, "bufferSizeSamples") ?: 4000

        // ── Noise resilience (optional) ──────────────────────────────────
        val highPassCutoffHz = resolveInt(json, "highPassCutoffHz") ?: 0
        val zcrEnabled = resolveBoolean(json, "zcrEnabled") ?: false

        // ── New public API fields (optional) ─────────────────────────────
        val sttMode = resolveString(json, "sttMode") ?: "ALWAYS_ON"
        val grammar = resolveString(json, "grammar")
        val partialsEnabled = resolveBoolean(json, "partialsEnabled") ?: false
        val autoReturn = resolveBoolean(json, "autoReturn") ?: false

        return SttConfig(
            modelPath = modelPath,
            language = language,
            debugLoggingEnabled = debugLoggingEnabled,
            energyThreshold = energyThreshold.toFloat(),
            preRollMs = preRollMs,
            stableChunkSizeMs = stableChunkSizeMs,
            drainMode = drainMode,
            startTrigger = startTrigger,
            stopTrigger = stopTrigger,
            sessionTimeoutMs = sessionTimeoutMs,
            warmupEnabled = warmupEnabled,
            warmupDurationMs = warmupDurationMs,
            bufferSizeSamples = bufferSizeSamples,
            highPassCutoffHz = highPassCutoffHz,
            zcrEnabled = zcrEnabled,
            sttMode = sttMode,
            grammar = grammar,
            partialsEnabled = partialsEnabled,
            autoReturn = autoReturn
        )
    }

    // ═════════════════════════════════════════════════════════════════════
    // Output: internal types → JSON
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Build a JSON result message string from internal components.
     *
     * Output shape:
     * ```json
     * {
     *   "type": "result",
     *   "text": "transcribed text",
     *   "code": "SUCCESS",
     *   "timing": {
     *     "vadActiveMs": 1200,
     *     "utteranceMs": 3200,
     *     "inferenceMs": 450,
     *     "totalMs": 5200
     *   }
     * }
     * ```
     */
    fun buildResultJson(
        text: String,
        code: SttReturnCode,
        timing: SttTimingSnapshot?
    ): String {
        val sb = StringBuilder()
        sb.append("{\"type\":\"result\",\"text\":\"")
        sb.append(escapeJson(text))
        sb.append("\",\"code\":\"")
        sb.append(code.name)
        sb.append('"')

        if (timing != null) {
            sb.append(",\"timing\":{")
            sb.append("\"vadActiveMs\":").append(timing.vadActiveMs).append(',')
            sb.append("\"utteranceMs\":").append(timing.utteranceDurationMs).append(',')
            if (timing.silencePaddingMs > 0) {
                sb.append("\"silencePaddingMs\":").append(timing.silencePaddingMs).append(',')
            }
            sb.append("\"inferenceMs\":").append(timing.inferenceMs).append(',')
            sb.append("\"totalMs\":").append(timing.totalPipelineMs)
            sb.append('}')
        }

        sb.append('}')
        return sb.toString()
    }

    /**
     * Build a JSON error message string from an internal error.
     *
     * Output shape:
     * ```json
     * {
     *   "type": "error",
     *   "category": "CONFIG_ERROR",
     *   "code": "MODEL_LOAD_FAILED",
     *   "message": "File not found at /data/app/model.bin",
     *   "details": ["modelPath=/data/app/model.bin"]
     * }
     * ```
     *
     * @param code Machine-readable error code.
     * @param message Human-readable error description.
     * @param details Optional human-readable diagnostic bullet points.
     */
    fun buildErrorJson(
        code: SttErrorCode,
        message: String,
        details: List<String> = emptyList()
    ): String {
        val sb = StringBuilder()
        sb.append("{\"type\":\"error\",\"category\":\"")
        sb.append(code.category.name)
        sb.append("\",\"code\":\"")
        sb.append(code.name)
        sb.append("\",\"message\":\"")
        sb.append(escapeJson(message))
        sb.append('"')
        if (details.isNotEmpty()) {
            sb.append(",\"details\":[")
            details.forEachIndexed { index, detail ->
                if (index > 0) sb.append(',')
                sb.append('"')
                sb.append(escapeJson(detail))
                sb.append('"')
            }
            sb.append(']')
        }
        sb.append('}')
        return sb.toString()
    }

    /**
     * Build a debug JSON message string (optional, used when debug logging is enabled).
     *
     * Output shape:
     * ```json
     * {
     *   "type": "debug",
     *   "message": "Pipeline state: CAPTURING"
     * }
     * ```
     */
    fun buildDebugJson(message: String): String {
        val sb = StringBuilder()
        sb.append("{\"type\":\"debug\",\"message\":\"")
        sb.append(escapeJson(message))
        sb.append("\"}")
        return sb.toString()
    }

    // ═════════════════════════════════════════════════════════════════════
    // Output: RuntimeSttConfig → JSON (for reconfiguration)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Build a flat JSON config string from a [RuntimeSttConfig] and model path.
     *
     * The output uses the flat format consumed by [parseConfig] and is suitable
     * for passing to [SpeechToText.reconfigure].
     */
    fun buildConfigJson(config: RuntimeSttConfig, modelPath: String): String {
        val sb = StringBuilder()
        sb.append('{')

        appendJsonString(sb, "modelPath", modelPath)
        sb.append(',')
        appendJsonFloat(sb, "energyThreshold", config.energyThreshold)
        sb.append(',')
        appendJsonInt(sb, "preRollMs", config.preRollMs)
        sb.append(',')
        appendJsonInt(sb, "stableChunkSizeMs", config.stableChunkSizeMs)
        sb.append(',')
        appendJsonString(sb, "drainMode", "DRAIN_FROM_NEXT_FRAME")
        sb.append(',')
        appendJsonString(sb, "startType", "MANUAL")
        sb.append(',')

        val stopType: String
        val silenceMs: Int
        val maxDurationMs: Int
        if (config.stopStrategy is AutoSilenceStop) {
            stopType = "AUTO_SILENCE"
            silenceMs = (config.stopStrategy as AutoSilenceStop).cfg.silenceMs
            maxDurationMs = (config.stopStrategy as AutoSilenceStop).cfg.maxDurationMs
        } else {
            stopType = "MANUAL"
            silenceMs = config.autoSilenceMs
            maxDurationMs = config.autoMaxDurationMs
        }

        appendJsonString(sb, "stopType", stopType)
        sb.append(',')
        appendJsonInt(sb, "silenceMs", silenceMs)
        sb.append(',')
        appendJsonInt(sb, "maxDurationMs", maxDurationMs)
        sb.append(',')
        appendJsonInt(sb, "sessionTimeoutMs", config.sessionTimeoutMs)
        sb.append(',')
        appendJsonBoolean(sb, "warmupEnabled", config.warmupEnabled)
        sb.append(',')
        appendJsonInt(sb, "warmupDurationMs", config.warmupDurationMs)
        sb.append(',')
        appendJsonInt(sb, "highPassCutoffHz", config.highPassCutoffHz)
        sb.append(',')
        appendJsonBoolean(sb, "zcrEnabled", config.zcrEnabled)
        sb.append(',')
        appendJsonBoolean(sb, "debugLoggingEnabled", config.debugLoggingEnabled)
        sb.append(',')
        appendJsonInt(sb, "bufferSizeSamples", 4000)
        sb.append(',')
        appendJsonString(sb, "language", "en")
        sb.append(',')
        appendJsonString(sb, "sttMode", config.sttMode)
        val grammarVal = config.grammar
        if (grammarVal != null) {
            sb.append(',')
            appendJsonString(sb, "grammar", grammarVal)
        }
        sb.append(',')
        appendJsonBoolean(sb, "partialsEnabled", config.partialsEnabled)
        sb.append(',')
        appendJsonBoolean(sb, "autoReturn", config.autoReturn)

        sb.append('}')
        return sb.toString()
    }

    /**
     * Append a JSON string key-value pair.
     */
    private fun appendJsonString(sb: StringBuilder, key: String, value: String) {
        sb.append('"')
        sb.append(key)
        sb.append("\":\"")
        sb.append(escapeJson(value))
        sb.append('"')
    }

    /**
     * Append a JSON integer key-value pair.
     */
    private fun appendJsonInt(sb: StringBuilder, key: String, value: Int) {
        sb.append('"')
        sb.append(key)
        sb.append("\":")
        sb.append(value)
    }

    /**
     * Append a JSON float key-value pair.
     */
    private fun appendJsonFloat(sb: StringBuilder, key: String, value: Float) {
        sb.append('"')
        sb.append(key)
        sb.append("\":")
        sb.append(value.toDouble())
    }

    /**
     * Append a JSON boolean key-value pair.
     */
    private fun appendJsonBoolean(sb: StringBuilder, key: String, value: Boolean) {
        sb.append('"')
        sb.append(key)
        sb.append("\":")
        sb.append(if (value) "true" else "false")
    }

    /**
     * Minimal JSON string escaping for string values.
     */
    private fun escapeJson(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    // ═════════════════════════════════════════════════════════════════════
    // Internal helpers — start/stop strategy parsing
    // ═════════════════════════════════════════════════════════════════════

    private fun parseStartTrigger(json: String): StartTrigger {
        val startType = resolveString(json, "startType")
            ?: resolveNestedString(json, "startStrategy", "type")
            ?: "MANUAL"

        return when (startType) {
            "MANUAL" -> StartTrigger.Manual
            "VAD_START" -> {
                val threshold = resolveNestedDouble(json, "startStrategy", "vadStartThreshold")
                    ?: throw IllegalArgumentException("VAD_START requires vadStartThreshold")
                val minSpeech = resolveNestedInt(json, "startStrategy", "minSpeechMs")
                    ?: throw IllegalArgumentException("VAD_START requires minSpeechMs")
                StartTrigger.VadStart(
                    vadStartThreshold = threshold.toFloat(),
                    minSpeechMs = minSpeech
                )
            }
            "WAKEWORD" -> {
                val wakeWord = resolveNestedString(json, "startStrategy", "wakeWord")
                    ?: throw IllegalArgumentException("WAKEWORD requires wakeWord")
                val confidence = resolveNestedDouble(json, "startStrategy", "confidenceThreshold")
                    ?: throw IllegalArgumentException("WAKEWORD requires confidenceThreshold")
                StartTrigger.WakeWordStart(
                    wakeWord = wakeWord,
                    confidenceThreshold = confidence.toFloat()
                )
            }
            else -> throw IllegalArgumentException("Unknown startType: $startType")
        }
    }

    private fun parseStopTrigger(json: String): StopTrigger {
        val stopType = resolveString(json, "stopType")
            ?: resolveNestedString(json, "stopStrategy", "type")
            ?: "MANUAL"

        return when (stopType) {
            "MANUAL" -> StopTrigger.Manual
            "AUTO_SILENCE" -> {
                val silenceMs = resolveInt(json, "silenceMs")
                    ?: resolveNestedInt(json, "stopStrategy", "silenceMs")
                    ?: throw IllegalArgumentException("AUTO_SILENCE requires silenceMs")
                val maxDurationMs = resolveInt(json, "maxDurationMs")
                    ?: resolveNestedInt(json, "stopStrategy", "maxDurationMs")
                    ?: throw IllegalArgumentException("AUTO_SILENCE requires maxDurationMs")
                StopTrigger.AutoSilence(
                    silenceMs = silenceMs,
                    maxDurationMs = maxDurationMs
                )
            }
            "DURATION" -> {
                val maxDurationMs = resolveInt(json, "maxDurationMs")
                    ?: resolveNestedInt(json, "stopStrategy", "maxDurationMs")
                    ?: throw IllegalArgumentException("DURATION requires maxDurationMs")
                StopTrigger.Duration(maxDurationMs = maxDurationMs)
            }
            else -> throw IllegalArgumentException("Unknown stopType: $stopType")
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Manual JSON value extraction helpers (no Android dependency)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Extract a string value for [key] from the top-level JSON object.
     */
    private fun resolveString(json: String, key: String): String? {
        val regex = Regex(""""$key"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        return regex.find(json)?.groupValues?.get(1)
    }

    /**
     * Extract an integer value for [key] from the top-level JSON object.
     */
    private fun resolveInt(json: String, key: String): Int? {
        val regex = Regex(""""$key"\s*:\s*(-?\d+)(?:[,\s}]|$)""")
        return regex.find(json)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * Extract a double value for [key] from the top-level JSON object.
     */
    private fun resolveDouble(json: String, key: String): Double? {
        val regex = Regex(""""$key"\s*:\s*(-?\d+(?:\.\d+)?)(?:[,\s}]|$)""")
        return regex.find(json)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    /**
     * Extract a boolean value for [key] from the top-level JSON object.
     */
    private fun resolveBoolean(json: String, key: String): Boolean? {
        val regex = Regex(""""$key"\s*:\s*(true|false)""")
        return regex.find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()
    }

    /**
     * Extract a string value from a nested JSON object: "parent": { "child": "value" }
     */
    private fun resolveNestedString(json: String, parent: String, child: String): String? {
        val content = extractNestedObject(json, parent) ?: return null
        val childRegex = Regex(""""$child"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        return childRegex.find(content)?.groupValues?.get(1)
    }

    /**
     * Extract an integer value from a nested JSON object.
     */
    private fun resolveNestedInt(json: String, parent: String, child: String): Int? {
        val content = extractNestedObject(json, parent) ?: return null
        val childRegex = Regex(""""$child"\s*:\s*(-?\d+)(?:[,\s}]|$)""")
        return childRegex.find(content)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * Extract a double value from a nested JSON object.
     */
    private fun resolveNestedDouble(json: String, parent: String, child: String): Double? {
        val content = extractNestedObject(json, parent) ?: return null
        val childRegex = Regex(""""$child"\s*:\s*(-?\d+(?:\.\d+)?)(?:[,\s}]|$)""")
        return childRegex.find(content)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    /**
     * Extract the raw content of a nested JSON object.
     * Returns the content between { and } of the matching parent object.
     */
    private fun extractNestedObject(json: String, parent: String): String? {
        val parentRegex = Regex(""""$parent"\s*:\s*\{""")
        val matchResult = parentRegex.find(json) ?: return null
        val startIndex = matchResult.range.last + 1
        val endIndex = findMatchingBrace(json, startIndex) ?: return null
        return json.substring(startIndex, endIndex)
    }

    /**
     * Find the matching closing brace for an opening brace at [startIndex].
     * Skips over string literals, handles nested braces.
     */
    private fun findMatchingBrace(json: String, startIndex: Int): Int? {
        var depth = 1
        var i = startIndex
        while (i < json.length && depth > 0) {
            when (json[i]) {
                '{' -> depth++
                '}' -> depth--
                '"' -> {
                    i++
                    while (i < json.length) {
                        if (json[i] == '\\') i++
                        else if (json[i] == '"') break
                        i++
                    }
                }
            }
            i++
        }
        return if (depth == 0) i - 1 else null
    }

    /**
     * Legacy nested format helpers (ttsEngineConfig).
     */
    private fun resolveNestedEngineString(json: String, key: String): String? {
        return resolveNestedString(json, "ttsEngineConfig", key)
    }

    private fun resolveNestedEngineBoolean(json: String, key: String): Boolean? {
        val content = extractNestedObject(json, "ttsEngineConfig") ?: return null
        val childRegex = Regex(""""$key"\s*:\s*(true|false)""")
        return childRegex.find(content)?.groupValues?.get(1)?.toBooleanStrictOrNull()
    }

    private fun resolveNestedVadDouble(json: String, key: String): Double? {
        return resolveNestedDouble(json, "vadConfig", key)
    }

    private fun resolveNestedVadInt(json: String, key: String): Int? {
        return resolveNestedInt(json, "vadConfig", key)
    }
}
