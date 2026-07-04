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

    fun pcm(msg: String) = Log.i(TAG, "[PCM] $msg")
    fun pcmD(msg: String) = Log.d(TAG, "[PCM] $msg")
    fun pcmW(msg: String) = Log.w(TAG, "[PCM] $msg")
    fun pcmE(msg: String) = Log.e(TAG, "[PCM] $msg")

    fun vad(msg: String) = Log.i(TAG, "[VAD] $msg")
    fun vadD(msg: String) = Log.d(TAG, "[VAD] $msg")
    fun vadW(msg: String) = Log.w(TAG, "[VAD] $msg")
    fun vadE(msg: String) = Log.e(TAG, "[VAD] $msg")

    fun whisper(msg: String) = Log.i(TAG, "[WHISPER] $msg")
    fun whisperD(msg: String) = Log.d(TAG, "[WHISPER] $msg")
    fun whisperW(msg: String) = Log.w(TAG, "[WHISPER] $msg")
    fun whisperE(msg: String) = Log.e(TAG, "[WHISPER] $msg")

    fun config(msg: String) = Log.i(TAG, "[CONFIG] $msg")
    fun configW(msg: String) = Log.w(TAG, "[CONFIG] $msg")
    fun configE(msg: String) = Log.e(TAG, "[CONFIG] $msg")

    fun lifecycle(msg: String) = Log.i(TAG, "[LIFECYCLE] $msg")
    fun lifecycleW(msg: String) = Log.w(TAG, "[LIFECYCLE] $msg")
    fun lifecycleE(msg: String) = Log.e(TAG, "[LIFECYCLE] $msg")

    fun error(msg: String) = Log.e(TAG, "[ERROR] $msg")
    fun errorW(msg: String) = Log.w(TAG, "[ERROR] $msg")

    // ── Internal ─────────────────────────────────────────────────────────

    private const val TAG = "STT"
}
