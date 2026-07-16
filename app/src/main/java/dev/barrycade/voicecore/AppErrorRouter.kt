package dev.barrycade.voicecore

import org.json.JSONObject

/**
 * Routes structured error JSON from the STT subsystem to UI actions and logs.
 *
 * ## Usage
 *
 * Callers parse the error JSON into a [JSONObject], then pass it here.
 * The router returns an [ErrorUiAction] describing what the UI should do.
 *
 * ```
 * val action = AppErrorRouter.route(errorJson)
 * AppLogger.log(action.logCode, *action.logArgs)
 * txtErrorBanner.visibility = if (action.showBanner) View.VISIBLE else View.GONE
 * txtErrorBanner.text = action.bannerText
 * txtOutput.text = action.outputText
 * ```
 *
 * ## Error category → UI mapping
 *
 * | Category       | Banner | Output                          |
 * |----------------|--------|---------------------------------|
 * | CONFIG_ERROR   | Show   | Descriptive message             |
 * | CAPTURE_ERROR  | Hide   | "Capture error: {message}"      |
 * | WHISPER_ERROR  | Hide   | "Inference error: {message}"    |
 * | VAD_ERROR      | Hide   | "VAD error: {message}"         |
 * | TIMEOUT        | Hide   | "Session timed out"             |
 * | UNKNOWN        | Hide   | "Error: {message}"              |
 *
 * ## Error code → AppLogCode mapping
 *
 * Every error JSON is logged via [AppLogger]. The mapping from the JSON
 * `"code"` field to [AppLogCode] is defined in [logCodeForErrorCode].
 *
 * No ad-hoc message strings. No inline parsing. One routing function.
 */
internal object AppErrorRouter {

    /**
     * Route an error JSON object to the appropriate UI action.
     *
     * @param errorJson A JSONObject with at minimum `"type":"error"`,
     *                  `"code"`, and `"message"` fields. May also contain
     *                  `"category"`.
     * @return An [ErrorUiAction] with display and logging instructions.
     */
    fun route(errorJson: JSONObject): ErrorUiAction {
        val code = errorJson.optString("code", "UNKNOWN")
        val message = errorJson.optString("message", "Unknown error")
        val category = errorJson.optString("category", "UNKNOWN")

        val details = if (errorJson.has("details")) {
            val arr = errorJson.getJSONArray("details")
            (0 until arr.length()).map { arr.getString(it) }
        } else {
            emptyList()
        }

        val logCode = logCodeForErrorCode(code)
        val (showBanner, outputText) = uiForCategory(category, code, message)

        val outputWithDetails = if (details.isNotEmpty()) {
            "$outputText\n\nDetails:\n${details.joinToString("\n")}"
        } else {
            outputText
        }

        return ErrorUiAction(
            showBanner = showBanner,
            bannerText = if (showBanner) "STT configuration error: $message\nCheck config and restart the app." else null,
            outputText = outputWithDetails,
            logCode = logCode,
            logArgs = arrayOf("$code: $message", *details.toTypedArray())
        )
    }

    // ── Internal mapping tables ─────────────────────────────────────────

    /**
     * Map the JSON `"category"` field to UI behaviour.
     *
     * Returns a pair of (showBanner, outputText).
     */
    private fun uiForCategory(
        category: String,
        code: String,
        message: String
    ): Pair<Boolean, String> {
        return when (category) {
            "CONFIG_ERROR" -> Pair(true, message)
            "CAPTURE_ERROR" -> Pair(false, "Capture error: $message")
            "WHISPER_ERROR" -> Pair(false, "Inference error: $message")
            "VAD_ERROR" -> Pair(false, "VAD error: $message")
            "TIMEOUT" -> Pair(false, "Session timed out")
            else -> Pair(false, "Error: $message")
        }
    }

    /**
     * Map the JSON `"code"` field to an [AppLogCode] for structured logging.
     *
     * Unknown codes default to [AppLogCode.ASYNC_ERROR].
     */
    private fun logCodeForErrorCode(code: String): AppLogCode {
        return when (code) {
            "CONFIG_PARSE_FAILED" -> AppLogCode.CONFIG_INVALID
            "MODEL_LOAD_FAILED" -> AppLogCode.INIT_FAILED
            "INFERENCE_FAILED" -> AppLogCode.ASYNC_ERROR
            "INFERENCE_TIMEOUT" -> AppLogCode.ASYNC_ERROR
            "CAPTURE_FAILED" -> AppLogCode.SESSION_ERROR
            "VAD_FAILED" -> AppLogCode.INTERNAL_ERROR
            "PIPELINE_ILLEGAL_STATE" -> AppLogCode.INTERNAL_ERROR
            "INTERNAL_EXCEPTION" -> AppLogCode.INTERNAL_ERROR
            else -> AppLogCode.ASYNC_ERROR
        }
    }
}

/**
 * Result of routing an error through [AppErrorRouter].
 *
 * The caller applies these to the UI:
 * - [showBanner] / [bannerText] — controls the error banner visibility and text.
 * - [outputText] — text to set on the main output view.
 * - [logCode] / [logArgs] — for [AppLogger.log].
 *
 * @property showBanner True if the persistent error banner should be shown.
 * @property bannerText Text for the error banner (null when banner is hidden).
 * @property outputText Text for the main output view.
 * @property logCode The [AppLogCode] for structured logging, or null to skip logging.
 * @property logArgs Arguments for the log template.
 */
internal data class ErrorUiAction(
    val showBanner: Boolean,
    val bannerText: String? = null,
    val outputText: String? = null,
    val logCode: AppLogCode? = null,
    val logArgs: Array<out Any?> = emptyArray()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ErrorUiAction) return false
        return showBanner == other.showBanner &&
                bannerText == other.bannerText &&
                outputText == other.outputText &&
                logCode == other.logCode &&
                logArgs.contentEquals(other.logArgs)
    }

    override fun hashCode(): Int {
        var result = showBanner.hashCode()
        result = 31 * result + (bannerText?.hashCode() ?: 0)
        result = 31 * result + (outputText?.hashCode() ?: 0)
        result = 31 * result + (logCode?.hashCode() ?: 0)
        result = 31 * result + logArgs.contentHashCode()
        return result
    }
}
