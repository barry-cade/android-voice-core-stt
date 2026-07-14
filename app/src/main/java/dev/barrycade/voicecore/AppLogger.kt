package dev.barrycade.voicecore

import android.util.Log

/**
 * App-module logger matching the STT subsystem logging format.
 *
 * All messages use the single tag "STT" with a mandatory module prefix [APP]
 * and a subsystem category bracket.
 *
 * Format: STT  I  [APP] [CATEGORY] CODE: message
 *
 * ## Usage
 *
 * Messages with fixed text (no runtime data):
 * ```
 * AppLogger.log(AppLogCode.START_BUTTON_PRESSED)
 * ```
 *
 * Messages with runtime values use printf-style templates in the enum:
 * ```
 * AppLogger.log(AppLogCode.START_SESSION_RESULT, sttResult.code)
 * ```
 *
 * ## No magic strings
 *
 * Every log message is defined inside [AppLogCode]. Callsites supply only the
 * enum constant and runtime data — never literal message text or ad-hoc tags.
 */
internal object AppLogger {

    /**
     * Emit a log line from the app module.
     *
     * The [code] provides the category, error code, and message template.
     * [args] are substituted into the template via [String.format].
     *
     * Output: `STT  I  [APP] [CATEGORY] CODE: formatted message`
     */
    fun log(code: AppLogCode, vararg args: Any?) {
        val message = if (args.isNotEmpty()) {
            String.format(code.template, *args)
        } else {
            code.template
        }
        safeLog { Log.i(TAG, "[APP] [${code.category}] ${code.logCode}: $message") }
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private const val TAG = "STT"

    private inline fun safeLog(action: () -> Int) {
        try {
            action()
        } catch (_: RuntimeException) {
            // android.util.Log may not be mocked in unit test environments.
        }
    }
}

/**
 * All app-module log codes.
 *
 * Each entry owns three things:
 * - [logCode] — the `CODE=` value in the log line (no magic strings).
 * - [template] — a `String.format` template for the human-readable message.
 * - [category] — the subsystem bracket, e.g. FLOW, CONFIG, ERROR.
 *
 * Entries with no runtime data use a plain string template.
 * Entries with runtime values use `%s` placeholders:
 * ```
 * AppLogger.log(AppLogCode.START_SESSION_RESULT, sttResult.code)
 * ```
 */
internal enum class AppLogCode(
    val logCode: String,
    val template: String,
    val category: String
) {
    // ── Flow events ─────────────────────────────────────────────────

    /** Start button pressed. */
    START_BUTTON_PRESSED("START_BUTTON_PRESSED", "Start button pressed", "FLOW"),

    /** Stop button pressed. */
    STOP_BUTTON_PRESSED("STOP_BUTTON_PRESSED", "Stop button pressed", "FLOW"),

    /** Obtaining singleton STT instance. */
    OBTAINING_STT_INSTANCE("OBTAINING_STT_INSTANCE", "Obtaining singleton STT instance", "FLOW"),

    /** startSession returned with a result code. */
    START_SESSION_RESULT("START_SESSION_RESULT", "startSession returned %s", "FLOW"),

    /** stopAndTranscribe has been invoked. */
    STOP_USING_STOP_AND_TRANSCRIBE("STOP_USING_STOP_AND_TRANSCRIBE", "STOP pressed -> using stopAndTranscribe()", "FLOW"),

    // ── Config events ───────────────────────────────────────────────

    /** Config asset failed to load. */
    CONFIG_LOAD_FAILED("CONFIG_LOAD_FAILED", "Failed to load config asset: %s", "CONFIG"),

    /** Config validation failed. */
    CONFIG_INVALID("CONFIG_INVALID", "Invalid STT configuration: %s", "CONFIG"),

    // ── Config error path ───────────────────────────────────────────

    /** setConfig returned non-SUCCESS. */
    SET_CONFIG_FAILED("SET_CONFIG_FAILED", "setConfig returned %s", "CONFIG"),

    /** initStt returned non-SUCCESS. */
    INIT_FAILED("INIT_FAILED", "initStt returned %s", "ERROR"),

    /** startSession returned non-SUCCESS. */
    SESSION_START_FAILED("SESSION_START_FAILED", "Session error: %s", "ERROR"),

    /** stopAndTranscribe threw an exception. */
    STOP_FAILED("STOP_FAILED", "stopAndTranscribe threw: %s", "ERROR"),

    // ── Generic internal error ──────────────────────────────────────

    /** Unexpected internal exception. */
    INTERNAL_ERROR("INTERNAL_ERROR", "Error: %s", "ERROR");
}
