package dev.barrycade.voicecore.stt

import org.json.JSONObject

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
     *   "warmupDurationMs": 3000
     * }
     * ```
     *
     * Also supports the legacy nested format used by [AppSttConfigLoader]:
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
     * @throws IllegalArgumentException if required fields are missing or invalid.
     */
    fun parseConfig(json: String): SttConfig {
        val root = JSONObject(json)

        // ── Model path & language ────────────────────────────────────────
        val modelPath = resolveString(root, "modelPath") ?: resolveNestedEngineField(root, "modelPath")
            ?: throw IllegalArgumentException("Missing required field: modelPath")
        val language = resolveString(root, "language") ?: resolveNestedEngineField(root, "language") ?: "en"
        val debugLoggingEnabled = resolveBoolean(root, "debugLoggingEnabled")
            ?: resolveNestedEngineFieldBoolean(root, "debugLoggingEnabled") ?: false

        // ── VAD config ───────────────────────────────────────────────────
        val energyThreshold = resolveDouble(root, "energyThreshold")
            ?: resolveNestedVadFieldDouble(root, "energyThreshold")
            ?: throw IllegalArgumentException("Missing required field: energyThreshold")
        val preRollMs = resolveInt(root, "preRollMs")
            ?: resolveNestedVadFieldInt(root, "preRollMs")
            ?: throw IllegalArgumentException("Missing required field: preRollMs")
        val stableChunkSizeMs = resolveInt(root, "stableChunkSizeMs")
            ?: resolveNestedVadFieldInt(root, "stableChunkSizeMs")
            ?: throw IllegalArgumentException("Missing required field: stableChunkSizeMs")

        // ── Drain mode ───────────────────────────────────────────────────
        val drainModeString = resolveString(root, "drainMode")
            ?: throw IllegalArgumentException("Missing required field: drainMode")
        val drainMode = try {
            DrainMode.valueOf(drainModeString)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException(
                "Invalid drainMode='$drainModeString'. Allowed: DRAIN_FROM_NEXT_FRAME, DRAIN_FROM_HEAD."
            )
        }

        // ── Start strategy ───────────────────────────────────────────────
        val startTrigger = parseStartTrigger(root)

        // ── Stop strategy ────────────────────────────────────────────────
        val stopTrigger = parseStopTrigger(root)

        // ── Warmup (optional) ────────────────────────────────────────────
        val warmupEnabled = resolveBoolean(root, "warmupEnabled") ?: false
        val warmupDurationMs = resolveInt(root, "warmupDurationMs") ?: 0

        // ── Buffer size (optional) ───────────────────────────────────────
        val bufferSizeSamples = resolveInt(root, "bufferSizeSamples") ?: 4000

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
            warmupEnabled = warmupEnabled,
            warmupDurationMs = warmupDurationMs,
            bufferSizeSamples = bufferSizeSamples
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
     *     "captureMs": 3200,
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
        val obj = JSONObject()
        obj.put("type", "result")
        obj.put("text", text)
        obj.put("code", code.name)

        if (timing != null) {
            val timingObj = JSONObject()
            timingObj.put("captureMs", timing.utteranceDurationMs)
            timingObj.put("inferenceMs", timing.inferenceMs)
            timingObj.put("totalMs", timing.totalPipelineMs)
            obj.put("timing", timingObj)
        }

        return obj.toString()
    }

    /**
     * Build a JSON error message string from an internal error.
     *
     * Output shape:
     * ```json
     * {
     *   "type": "error",
     *   "code": "MODEL_LOAD_FAILED",
     *   "message": "File not found at /data/app/model.bin"
     * }
     * ```
     */
    fun buildErrorJson(code: String, message: String): String {
        val obj = JSONObject()
        obj.put("type", "error")
        obj.put("code", code)
        obj.put("message", message)
        return obj.toString()
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
        val obj = JSONObject()
        obj.put("type", "debug")
        obj.put("message", message)
        return obj.toString()
    }

    // ═════════════════════════════════════════════════════════════════════
    // Internal helpers — start/stop strategy parsing
    // ═════════════════════════════════════════════════════════════════════

    private fun parseStartTrigger(root: JSONObject): StartTrigger {
        // Support both flat (startType) and nested (startStrategy.type) formats
        val startType = resolveString(root, "startType")
            ?: root.optJSONObject("startStrategy")?.optString("type")
            ?: "MANUAL"

        val startObj = root.optJSONObject("startStrategy") ?: root

        return when (startType) {
            "MANUAL" -> StartTrigger.Manual
            "VAD_START" -> {
                val threshold = startObj.optDouble("vadStartThreshold", -1.0)
                val minSpeech = startObj.optInt("minSpeechMs", -1)
                if (threshold < 0 || minSpeech < 0) {
                    throw IllegalArgumentException(
                        "VAD_START requires vadStartThreshold and minSpeechMs"
                    )
                }
                StartTrigger.VadStart(
                    vadStartThreshold = threshold.toFloat(),
                    minSpeechMs = minSpeech
                )
            }
            "WAKEWORD" -> {
                val wakeWord = startObj.optString("wakeWord", "")
                val confidence = startObj.optDouble("confidenceThreshold", -1.0)
                if (wakeWord.isEmpty() || confidence < 0) {
                    throw IllegalArgumentException(
                        "WAKEWORD requires wakeWord and confidenceThreshold"
                    )
                }
                StartTrigger.WakeWordStart(
                    wakeWord = wakeWord,
                    confidenceThreshold = confidence.toFloat()
                )
            }
            else -> throw IllegalArgumentException("Unknown startType: $startType")
        }
    }

    private fun parseStopTrigger(root: JSONObject): StopTrigger {
        // Support both flat (stopType) and nested (stopStrategy.type) formats
        val stopType = resolveString(root, "stopType")
            ?: root.optJSONObject("stopStrategy")?.optString("type")
            ?: "MANUAL"

        val stopObj = root.optJSONObject("stopStrategy") ?: root

        return when (stopType) {
            "MANUAL" -> StopTrigger.Manual
            "AUTO_SILENCE" -> {
                val silenceMs = resolveInt(stopObj, "silenceMs")
                    ?: throw IllegalArgumentException("AUTO_SILENCE requires silenceMs")
                val maxDurationMs = resolveInt(stopObj, "maxDurationMs")
                    ?: throw IllegalArgumentException("AUTO_SILENCE requires maxDurationMs")
                StopTrigger.AutoSilence(
                    silenceMs = silenceMs,
                    maxDurationMs = maxDurationMs
                )
            }
            "DURATION" -> {
                val maxDurationMs = resolveInt(stopObj, "maxDurationMs")
                    ?: throw IllegalArgumentException("DURATION requires maxDurationMs")
                StopTrigger.Duration(maxDurationMs = maxDurationMs)
            }
            else -> throw IllegalArgumentException("Unknown stopType: $stopType")
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Generic JSON resolution helpers
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Resolve a string field that may live at the root or inside
     * a nested "ttsEngineConfig" object (legacy format).
     */
    private fun resolveString(root: JSONObject, key: String): String? {
        if (root.has(key)) {
            return root.getString(key)
        }
        return null
    }

    private fun resolveInt(root: JSONObject, key: String): Int? {
        if (root.has(key)) {
            return root.getInt(key)
        }
        return null
    }

    private fun resolveDouble(root: JSONObject, key: String): Double? {
        if (root.has(key)) {
            return root.getDouble(key)
        }
        return null
    }

    private fun resolveBoolean(root: JSONObject, key: String): Boolean? {
        if (root.has(key)) {
            return root.getBoolean(key)
        }
        return null
    }

    private fun resolveNestedEngineField(root: JSONObject, key: String): String? {
        val engine = root.optJSONObject("ttsEngineConfig") ?: return null
        return if (engine.has(key)) engine.getString(key) else null
    }

    private fun resolveNestedEngineFieldBoolean(root: JSONObject, key: String): Boolean? {
        val engine = root.optJSONObject("ttsEngineConfig") ?: return null
        return if (engine.has(key)) engine.getBoolean(key) else null
    }

    private fun resolveNestedVadFieldDouble(root: JSONObject, key: String): Double? {
        val vad = root.optJSONObject("vadConfig") ?: return null
        return if (vad.has(key)) {
            vad.get(key).let { value ->
                when (value) {
                    is Number -> value.toDouble()
                    else -> throw IllegalArgumentException("Expected numeric value for $key")
                }
            }
        } else {
            null
        }
    }

    private fun resolveNestedVadFieldInt(root: JSONObject, key: String): Int? {
        val vad = root.optJSONObject("vadConfig") ?: return null
        return if (vad.has(key)) vad.getInt(key) else null
    }
}
