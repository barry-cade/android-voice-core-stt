package dev.barrycade.voicecore

import android.util.Log

/**
 * App-module logger matching the STT subsystem logging format.
 *
 * All messages embed the module prefix [APP] with a subsystem category bracket.
 *
 * Format: APP  I  [APP] [CATEGORY] CODE: message
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
 * AppLogger.log(AppLogCode.INIT_FAILED, "MODEL_NOT_FOUND")
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
     * Output without args: `APP  I  [APP] [CATEGORY] CODE`
     * Output with args:   `APP  I  [APP] [CATEGORY] CODE: formatted message`
     */
    fun log(code: AppLogCode, vararg args: Any?) {
        safeLog {
            if (args.isNotEmpty()) {
                val message = String.format(code.template, *args)
                Log.i(TAG, "[APP] [${code.category}] ${code.logCode}: $message")
            } else {
                Log.i(TAG, "[APP] [${code.category}] ${code.logCode}")
            }
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private const val TAG = "APP"

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
 * - [category] — the subsystem bracket, e.g. FLOW, CONFIG_ERROR, PIPELINE_ERROR.
 *
 * Entries with no runtime data use a plain string template.
 * Entries with runtime values use `%s` placeholders:
 * ```
 * AppLogger.log(AppLogCode.INIT_FAILED, "MODEL_NOT_FOUND")
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

    /** transcribe has been invoked. */
    STOP_USING_STOP_AND_TRANSCRIBE("STOP_USING_STOP_AND_TRANSCRIBE", "STOP pressed -> using transcribe()", "FLOW"),

    /** Blank audio threshold breached (3+ consecutive blanks). */
    BLANK_AUDIO_THRESHOLD("BLANK_AUDIO_THRESHOLD", "Blank audio threshold breached (%s consecutive blanks)", "FLOW"),

    // ── Config errors ──────────────────────────────────────────────

    /** Config validation failed. */
    CONFIG_INVALID("CONFIG_INVALID", "Invalid STT configuration: %s", "CONFIG_ERROR"),

    /** Model preload at startup failed. */
    PRELOAD_FAILED("PRELOAD_FAILED", "Model preload failed: %s", "CONFIG_ERROR"),

    // ── Pipeline errors ────────────────────────────────────────────

    /** init returned non-SUCCESS. */
    INIT_FAILED("INIT_FAILED", "init returned %s", "PIPELINE_ERROR"),

    /** transcribe threw an exception. */
    STOP_FAILED("STOP_FAILED", "transcribe threw: %s", "PIPELINE_ERROR"),

    /** startSession() returned an error JSON. */
    SESSION_ERROR("SESSION_ERROR", "Session error: %s", "PIPELINE_ERROR"),

    /** Async error received via JSON listener during capture. */
    ASYNC_ERROR("ASYNC_ERROR", "Async error: %s", "PIPELINE_ERROR"),

    // ── Generic internal error ──────────────────────────────────────

    /** Unexpected internal exception. */
    INTERNAL_ERROR("INTERNAL_ERROR", "Error: %s", "INTERNAL_ERROR"),

    // ── Audio test service ─────────────────────────────────────────

    /** Audio test service started. */
    AUDIO_TEST_SERVICE_STARTED("AUDIO_TEST_SERVICE_STARTED", "AudioTestService running — direct AudioCapture access removed. Use SpeechToText.init() instead.", "AUDIO");
}

