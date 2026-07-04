package dev.barrycade.voicecore.stt

import android.util.Log

/**
 * Unified logger for the STT subsystem.
 *
 * All log messages use the single tag "STT" and begin with a mandatory prefix
 * that identifies the originating subsystem component:
 *   [PCM], [VAD], [WHISPER], [CONFIG], [LIFECYCLE], [ERROR]
 *
 * Example:
 *   SttLogger.pcm("startRecording: sampleRate=16000, bufferSize=32000")
 *   → Log.i("STT", "[PCM] startRecording: sampleRate=16000, bufferSize=32000")
 */
internal object SttLogger {

    // ── Subsystem log methods ────────────────────────────────────────────

    fun pcm(msg: String) = safeLog { Log.i(TAG, "[PCM] $msg") }
    fun pcmD(msg: String) = safeLog { Log.d(TAG, "[PCM] $msg") }
    fun pcmW(msg: String) = safeLog { Log.w(TAG, "[PCM] $msg") }
    fun pcmE(msg: String) = safeLog { Log.e(TAG, "[PCM] $msg") }

    fun vad(msg: String) = safeLog { Log.i(TAG, "[VAD] $msg") }
    fun vadD(msg: String) = safeLog { Log.d(TAG, "[VAD] $msg") }
    fun vadW(msg: String) = safeLog { Log.w(TAG, "[VAD] $msg") }
    fun vadE(msg: String) = safeLog { Log.e(TAG, "[VAD] $msg") }

    fun whisper(msg: String) = safeLog { Log.i(TAG, "[WHISPER] $msg") }
    fun whisperD(msg: String) = safeLog { Log.d(TAG, "[WHISPER] $msg") }
    fun whisperW(msg: String) = safeLog { Log.w(TAG, "[WHISPER] $msg") }
    fun whisperE(msg: String) = safeLog { Log.e(TAG, "[WHISPER] $msg") }

    fun config(msg: String) = safeLog { Log.i(TAG, "[CONFIG] $msg") }
    fun configW(msg: String) = safeLog { Log.w(TAG, "[CONFIG] $msg") }
    fun configE(msg: String) = safeLog { Log.e(TAG, "[CONFIG] $msg") }

    fun lifecycle(msg: String) = safeLog { Log.i(TAG, "[LIFECYCLE] $msg") }
    fun lifecycleW(msg: String) = safeLog { Log.w(TAG, "[LIFECYCLE] $msg") }
    fun lifecycleE(msg: String) = safeLog { Log.e(TAG, "[LIFECYCLE] $msg") }

    fun error(msg: String) = safeLog { Log.e(TAG, "[ERROR] $msg") }
    fun errorW(msg: String) = safeLog { Log.w(TAG, "[ERROR] $msg") }

    // ── Internal ─────────────────────────────────────────────────────────

    private const val TAG = "STT"

    /**
     * Safe logging wrapper that handles unmocked android.util.Log in unit tests.
     * Silently ignores RuntimeException caused by "not mocked" stubs.
     */
    private inline fun safeLog(action: () -> Int) {
        try {
            action()
        } catch (_: RuntimeException) {
            // android.util.Log may not be mocked in unit test environments.
            // Silently suppress — logging is informative, not functional.
        }
    }
}
