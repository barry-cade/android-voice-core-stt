package dev.barrycade.voicecore.stt

import android.util.Log

/**
 * Unified logger for the STT subsystem.
 *
 * All log messages use the single tag "STT" and embed the module prefix [STT]
 * followed by a subsystem component prefix: [PCM], [VAD], [WHISPER], [CONFIG],
 * [LIFECYCLE], [ERROR].
 *
 * Example:
 *   SttLogger.pcm("startRecording: sampleRate=16000, bufferSize=32000")
 *   → Log.i("STT", "[STT] [PCM] startRecording: sampleRate=16000, bufferSize=32000")
 */
internal object SttLogger {

    // ── Subsystem log methods ────────────────────────────────────────────

    fun pcm(msg: String) = safeLog { Log.i(TAG, "[STT] [PCM] $msg") }
    fun pcmD(msg: String) = safeLog { Log.d(TAG, "[STT] [PCM] $msg") }
    fun pcmW(msg: String) = safeLog { Log.w(TAG, "[STT] [PCM] $msg") }
    fun pcmE(msg: String) = safeLog { Log.e(TAG, "[STT] [PCM] $msg") }
    fun pcmE(msg: String, throwable: Throwable) = safeLog { Log.e(TAG, "[STT] [PCM] $msg", throwable) }

    fun vad(msg: String) = safeLog { Log.i(TAG, "[STT] [VAD] $msg") }
    fun vadD(msg: String) = safeLog { Log.d(TAG, "[STT] [VAD] $msg") }
    fun vadW(msg: String) = safeLog { Log.w(TAG, "[STT] [VAD] $msg") }
    fun vadE(msg: String) = safeLog { Log.e(TAG, "[STT] [VAD] $msg") }

    fun whisper(msg: String) = safeLog { Log.i(TAG, "[STT] [WHISPER] $msg") }
    fun whisperD(msg: String) = safeLog { Log.d(TAG, "[STT] [WHISPER] $msg") }
    fun whisperW(msg: String) = safeLog { Log.w(TAG, "[STT] [WHISPER] $msg") }
    fun whisperE(msg: String) = safeLog { Log.e(TAG, "[STT] [WHISPER] $msg") }
    fun whisperE(msg: String, throwable: Throwable) = safeLog { Log.e(TAG, "[STT] [WHISPER] $msg", throwable) }

    fun config(msg: String) = safeLog { Log.i(TAG, "[STT] [CONFIG] $msg") }
    fun configW(msg: String) = safeLog { Log.w(TAG, "[STT] [CONFIG] $msg") }
    fun configE(msg: String) = safeLog { Log.e(TAG, "[STT] [CONFIG] $msg") }

    fun lifecycle(msg: String) = safeLog { Log.i(TAG, "[STT] [LIFECYCLE] $msg") }
    fun lifecycleW(msg: String) = safeLog { Log.w(TAG, "[STT] [LIFECYCLE] $msg") }
    fun lifecycleE(msg: String) = safeLog { Log.e(TAG, "[STT] [LIFECYCLE] $msg") }

    fun error(msg: String) = safeLog { Log.e(TAG, "[STT] [ERROR] $msg") }
    fun errorW(msg: String) = safeLog { Log.w(TAG, "[STT] [ERROR] $msg") }

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

