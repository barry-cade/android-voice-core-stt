package dev.barrycade.voicecore.vosk

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Represents the operational mode of the Vosk subsystem.
 *
 * Only one mode is active at any time. The manager enforces
 * mode exclusivity and prevents runaway threads.
 */
enum class VoskMode {
    IDLE,
    WAKEWORD,
    COMMAND
}

/**
 * Callback for partial recognition results.
 * Delivered on the main thread via [Handler].
 */
fun interface VoskPartialListener {
    fun onPartialResult(text: String)
}

/**
 * Callback for final recognition results.
 * Delivered on the main thread via [Handler].
 */
fun interface VoskFinalListener {
    fun onFinalResult(text: String)
}

/**
 * Manages Vosk capture sessions with explicit lifecycle control.
 *
 * Owns the [AudioRecord] thread and [VoskEngine] usage, ensuring
 * only one capture session is active at a time. The UI calls
 * [startWakeWordMode], [startCommandMode], or [stop] — it never
 * touches AudioRecord or VoskEngine directly.
 *
 * @param voskEngine Initialised VoskEngine instance (model loaded).
 * @param sampleRate Audio sample rate in Hz (default 16000).
 * @param bufferSizeSamples Number of short samples per read chunk (default 4000).
 */
class VoskSessionManager(
    private val voskEngine: VoskEngine,
    private val sampleRate: Int = 16000,
    private val bufferSizeSamples: Int = 4000
) {
    // ── Public callbacks ─────────────────────────────────────────────────────

    /** Called on the main thread for every partial result. */
    var partialListener: VoskPartialListener? = null

    /** Called on the main thread for every final result. */
    var finalListener: VoskFinalListener? = null

    /**
     * Called when the capture thread encounters an error.
     * Receives a human-readable message. The manager will
     * return to [VoskMode.IDLE] after this callback.
     */
    var errorListener: ((String) -> Unit)? = null

    // ── State ────────────────────────────────────────────────────────────────

    /** Current operational mode. Read from any thread. */
    @Volatile
    var mode: VoskMode = VoskMode.IDLE
        private set

    private val active = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var captureThread: Thread? = null

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Start a wake-word capture session.
     *
     * In this mode the recogniser continuously processes incoming audio
     * and reports partial results via [partialListener]. The mode persists
     * until [stop] is called or an error occurs.
     *
     * @throws IllegalStateException if a session is already active.
     */
    fun startWakeWordMode() {
        if (active.get()) {
            throw IllegalStateException("Vosk session already active in mode: $mode")
        }
        mode = VoskMode.WAKEWORD
        startCapture()
    }

    /**
     * Start a command capture session.
     *
     * In this mode the recogniser runs until a final result is produced
     * (utterance end), at which point [finalListener] is called and the
     * session automatically stops. If [stop] is called before a final
     * result, the session ends without a callback.
     *
     * @throws IllegalStateException if a session is already active.
     */
    fun startCommandMode() {
        if (active.get()) {
            throw IllegalStateException("Vosk session already active in mode: $mode")
        }
        mode = VoskMode.COMMAND
        startCapture()
    }

    /**
     * Stop the current session and return to [VoskMode.IDLE].
     *
     * Safe to call when no session is active (no-op in that case).
     */
    fun stop() {
        active.set(false)
        mode = VoskMode.IDLE
        // Thread will exit its loop and terminate naturally.
    }

    /**
     * Release all resources held by this manager.
     *
     * Stops any active session and releases the [VoskEngine].
     * After calling this, the manager must not be reused.
     */
    fun destroy() {
        stop()
        // Thread cleanup is handled by the loop exit.
        // VoskEngine is owned by the caller — they close it.
    }

    // ── Internal capture loop ────────────────────────────────────────────────

    private fun startCapture() {
        active.set(true)

        captureThread = Thread({
            runCaptureLoop()
        }, "VoskCaptureThread")

        captureThread?.start()
    }

    private fun runCaptureLoop() {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferBytes = maxOf(minBufferBytes, bufferSizeSamples * 2)

        val audioRecord: AudioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes
            )
        } catch (e: Exception) {
            postError("AudioRecord creation failed: ${e.message}")
            return
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            postError("AudioRecord not initialised.")
            return
        }

        audioRecord.startRecording()

        val shortBuffer = ShortArray(bufferSizeSamples)
        var frameCount = 0

        try {
            while (active.get()) {
                val readCount = audioRecord.read(shortBuffer, 0, shortBuffer.size)
                if (readCount <= 0) continue

                frameCount++

                val pcmChunk = if (readCount < shortBuffer.size) {
                    shortBuffer.copyOf(readCount)
                } else {
                    shortBuffer
                }

                val utteranceEnd: Boolean = try {
                    voskEngine.acceptShort(pcmChunk)
                } catch (t: Throwable) {
                    postError("Vosk accept failed: ${t.message}")
                    break
                }

                if (utteranceEnd) {
                    if (mode == VoskMode.COMMAND) {
                        val finalText = voskEngine.finalResult()
                        postFinal(finalText)
                        // Command mode ends after one utterance.
                        break
                    } else {
                        // Wake-word mode: log final but keep going.
                        val finalText = voskEngine.finalResult()
                        postFinal(finalText)
                    }
                } else {
                    val partial = voskEngine.partialResult()
                    postPartial(partial)
                }
            }
        } finally {
            audioRecord.stop()
            audioRecord.release()
            active.set(false)
            mode = VoskMode.IDLE
        }
    }

    // ── Main-thread dispatching ──────────────────────────────────────────────

    private fun postPartial(text: String) {
        val listener = partialListener
        if (listener == null) return
        mainHandler.post {
            listener.onPartialResult(text)
        }
    }

    private fun postFinal(text: String) {
        val listener = finalListener
        if (listener == null) return
        mainHandler.post {
            listener.onFinalResult(text)
        }
    }

    private fun postError(message: String) {
        mainHandler.post {
            errorListener?.invoke(message)
        }
    }
}
