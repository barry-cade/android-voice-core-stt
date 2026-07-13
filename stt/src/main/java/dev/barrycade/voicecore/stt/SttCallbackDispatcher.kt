package dev.barrycade.voicecore.stt

/**
 * Owns result and error callback invocation.
 *
 * Responsibilities:
 * - Dispatch transcription results via [dispatchResult].
 * - Dispatch errors via [dispatchError].
 * - Dispatch timing notifications via [dispatchTiming].
 * - Ensure listener safety (null checks).
 *
 * No lifecycle, no threading, no mode branching — only callback dispatch.
 *
 * ## Delivery thread
 *
 * Callbacks are invoked on the caller's thread. For timing and result
 * callbacks, this is typically the Whisper executor thread. For error
 * callbacks, it is the thread that encountered the error.
 * Callers must post to their own Handler or Dispatchers.Main if
 * main-thread delivery is required.
 */
internal class SttCallbackDispatcher {

    private val listenerLock = Any()

    /** Result listener: receives transcribed text. */
    private var onResult: ((String) -> Unit)? = null

    /** Result-with-timing listener: receives text, return code, and optional timing snapshot. */
    private var onResultWithTiming: ((text: String, code: SttReturnCode, timing: SttTimingSnapshot?) -> Unit)? = null

    /** Generic error listener. */
    private var onError: ((Throwable) -> Unit)? = null

    /** Structured STT error listener. */
    private var sttErrorListener: SttErrorListener? = null

    /** Timing listener backing field. */
    private var timingListener: ((pcmMs: Long, vadActiveMs: Long, whisperMs: Long, totalMs: Long) -> Unit)? = null

    /**
     * Timing listener, called after each inference completes.
     *
     * @param pcmMs Wall-clock duration of PCM capture.
     * @param vadActiveMs Total time speech was detected by VAD.
     * @param whisperMs Duration of the Whisper inference call.
     * @param totalMs End-to-end pipeline time from utterance start to result.
     */
    var onTimingListener: ((pcmMs: Long, vadActiveMs: Long, whisperMs: Long, totalMs: Long) -> Unit)?
        get() = synchronized(listenerLock) {
            timingListener
        }
        set(value) {
            synchronized(listenerLock) {
                timingListener = value
            }
        }

    // ── Listener registration ────────────────────────────────────────────

    /** Register a result listener. */
    fun setOnResultListener(l: (String) -> Unit) {
        synchronized(listenerLock) {
            onResult = l
        }
    }

    /** Register a result-with-timing listener. */
    fun setOnResultWithTimingListener(l: (text: String, code: SttReturnCode, timing: SttTimingSnapshot?) -> Unit) {
        synchronized(listenerLock) {
            onResultWithTiming = l
        }
    }

    /** Register a generic error listener. */
    fun setOnErrorListener(l: (Throwable) -> Unit) {
        synchronized(listenerLock) {
            onError = l
        }
    }

    /** Register a structured STT error listener. */
    fun setSttErrorListener(l: SttErrorListener) {
        synchronized(listenerLock) {
            sttErrorListener = l
        }
    }

    /**
     * Get the current STT error listener (for forwarding to components).
     */
    fun getSttErrorListener(): SttErrorListener? {
        return synchronized(listenerLock) {
            sttErrorListener
        }
    }

    // ── Dispatch methods ─────────────────────────────────────────────────

    /**
     * Dispatch a transcription result to all registered listeners.
     *
     * @param text The transcribed text.
     * @param code The return code categorising the result.
     * @param timing Optional timing snapshot (non-null during full pipeline runs;
     *               null during early stop paths that bypass the accumulator).
     */
    fun dispatchResult(text: String, code: SttReturnCode, timing: SttTimingSnapshot?) {
        val withTimingSnapshot = synchronized(listenerLock) {
            onResultWithTiming
        }
        val resultSnapshot = synchronized(listenerLock) {
            onResult
        }
        withTimingSnapshot?.invoke(text, code, timing)
        resultSnapshot?.invoke(text)
    }

    /**
     * Dispatch an error to all registered error listeners.
     *
     * @param t The throwable representing the error.
     */
    fun dispatchError(t: Throwable) {
        val errorSnapshot = synchronized(listenerLock) {
            onError
        }
        val sttErrorSnapshot = synchronized(listenerLock) {
            sttErrorListener
        }
        errorSnapshot?.invoke(t)
        sttErrorSnapshot?.onSttError(
            SttError(
                SttErrorCategory.UNKNOWN,
                SttErrorCode.INTERNAL_EXCEPTION,
                t.message ?: "Unknown error",
                cause = t
            )
        )
    }

    /**
     * Dispatch timing notification to the [onTimingListener].
     *
     * @param captureMs PCM capture wall-clock duration (ms).
     * @param vadActiveMs Total time speech was detected by VAD (ms).
     * @param whisperMs Duration of the Whisper inference call (ms).
     * @param totalMs End-to-end pipeline time (ms).
     */
    fun dispatchTiming(captureMs: Long, vadActiveMs: Long, whisperMs: Long, totalMs: Long) {
        val timingSnapshot = synchronized(listenerLock) {
            timingListener
        }
        timingSnapshot?.invoke(captureMs, vadActiveMs, whisperMs, totalMs)
    }

    /**
     * Clear all listeners. Called during destroy to prevent memory leaks.
     */
    fun clearListeners() {
        synchronized(listenerLock) {
            onResult = null
            onResultWithTiming = null
            onError = null
            sttErrorListener = null
            timingListener = null
        }
    }
}
