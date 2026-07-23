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
    HOTWORD,
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
 * Callback for hot-word detection.
 *
 * Fired on the main thread when the hot word is spotted
 * in a partial result during [VoskMode.HOTWORD] mode.
 */
fun interface VoskHotWordListener {
    fun onHotWordDetected()
}

/**
 * Manages Vosk capture sessions with explicit lifecycle control.
 *
 * Owns the [AudioRecord] thread and [VoskEngine] usage, ensuring
 * only one capture session is active at a time. The UI calls
 * [startHotWordMode], [startCommandMode], or [stop] — it never
 * touches AudioRecord or VoskEngine directly.
 *
 * In hot-word mode the manager continuously listens for a trigger word
 * in partial results. On detection it auto-switches to command mode,
 * captures one utterance, then returns to hot-word mode,
 * creating a seamless hands-free loop.
 *
 * @param voskEngine Initialised VoskEngine instance (model loaded).
 * @param config Configuration including trigger word, buffer size, sample rate.
 */
class VoskSessionManager(
    private val voskEngine: VoskEngine,
    private val config: VoskConfig = VoskConfig(
        modelPath = "",
        wakeWord = "Max",
        bufferSizeSamples = 4000,
        sampleRate = 16000f
    )
) {
    // ── Public callbacks ─────────────────────────────────────────────────────

    /** Called on the main thread for every partial result. */
    var partialListener: VoskPartialListener? = null

    /** Called on the main thread for every final result. */
    var finalListener: VoskFinalListener? = null

    /** Called on the main thread when the hot word is detected. */
    var hotWordListener: VoskHotWordListener? = null

    /**
     * Called on the main thread whenever the mode changes.
     * Receives the new [VoskMode] value. Fires after the mode
     * has been updated internally.
     */
    var modeListener: ((VoskMode) -> Unit)? = null

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

    /**
     * Set by the hot-word detection path to indicate we just
     * auto-switched to command mode. Prevents re-triggering the
     * hot word callback from partial results during the command
     * utterance.
     */
    @Volatile
    private var autoSwitchedToCommand: Boolean = false

    // ── Derived values from config ───────────────────────────────────────────

    private val sampleRate: Int = config.sampleRate.toInt()
    private val bufferSizeSamples: Int = config.bufferSizeSamples
    private val hotWord: String = config.wakeWord

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Start a hot-word capture session with automatic command mode switching.
     *
     * In this mode the recogniser continuously processes incoming audio
     * and reports partial results via [partialListener]. When the hot
     * word is detected the manager auto-switches to command mode, captures
     * one utterance, then returns to hot-word mode. This loop continues
     * until [stop] is called or an error occurs.
     *
     * @throws IllegalStateException if a session is already active.
     */
    fun startHotWordMode() {
        if (active.get()) {
            throw IllegalStateException("Vosk session already active in mode: $mode")
        }
        mode = VoskMode.HOTWORD
        postModeChange(VoskMode.HOTWORD)
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
        postModeChange(VoskMode.COMMAND)
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
        postModeChange(VoskMode.IDLE)
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

        val thread = Thread({
            runCaptureLoop()
        }, "VoskCaptureThread")

        captureThread = thread
        thread.start()
    }

    private fun runCaptureLoop() {
        // ── Outer loop: stays alive across hot-word → command → hot-word ──
        while (active.get()) {
            val audioRecord = createAudioRecord() ?: return
            var keepGoing = true

            if (mode == VoskMode.HOTWORD) {
                keepGoing = runHotWordInnerLoop(audioRecord)
            } else if (mode == VoskMode.COMMAND) {
                keepGoing = runCommandInnerLoop(audioRecord)
            }

            audioRecord.stop()
            audioRecord.release()

            if (!keepGoing) {
                // Command mode completed one utterance or stop was requested.
                if (!active.get()) {
                    // Explicit stop — exit completely.
                    mode = VoskMode.IDLE
                    return
                }

                // Auto-switch back to hot-word mode.
                mode = VoskMode.HOTWORD
                autoSwitchedToCommand = false
                postModeChange(VoskMode.HOTWORD)
                // Continue outer loop to re-create AudioRecord and listen again.
            }
        }

        mode = VoskMode.IDLE
        postModeChange(VoskMode.IDLE)
    }

    /**
     * Inner loop for hot-word mode.
     *
     * Reads audio frames, feeds them to Vosk, and checks partial results
     * for the hot word. Returns `true` to continue the outer loop
     * (normal exit due to hot-word detection → command mode).
     * Returns `false` on explicit stop or error.
     */
    private fun runHotWordInnerLoop(audioRecord: AudioRecord): Boolean {
        val shortBuffer = ShortArray(bufferSizeSamples)

        while (active.get() && mode == VoskMode.HOTWORD) {
            val readCount = audioRecord.read(shortBuffer, 0, shortBuffer.size)
            if (readCount <= 0) continue

            val pcmChunk = if (readCount < shortBuffer.size) {
                shortBuffer.copyOf(readCount)
            } else {
                shortBuffer
            }

            val utteranceEnd: Boolean = try {
                voskEngine.acceptShort(pcmChunk)
            } catch (t: Throwable) {
                postError("Vosk accept failed: ${t.message}")
                return false
            }

            if (utteranceEnd) {
                val finalText = voskEngine.finalResult()
                postFinal(finalText)
            } else {
                val partial = voskEngine.partialResult()
                    postPartial(partial)

                if (containsHotWord(partial)) {
                    // Hot word spotted — switch to command mode.
                    mode = VoskMode.COMMAND
                    autoSwitchedToCommand = true
                    postModeChange(VoskMode.COMMAND)
                    postHotWordDetected()
                    return true
                }
            }
        }

        // Stop requested while in hot-word mode.
        return false
    }

    /**
     * Inner loop for command mode.
     *
     * Reads audio frames until an utterance-end is reported by Vosk
     * or a stop is requested. Returns `true` to continue outer loop
     * (normal command completion → back to hot-word).
     * Returns `false` on explicit stop or error.
     */
    private fun runCommandInnerLoop(audioRecord: AudioRecord): Boolean {
        val shortBuffer = ShortArray(bufferSizeSamples)

        while (active.get() && mode == VoskMode.COMMAND) {
            val readCount = audioRecord.read(shortBuffer, 0, shortBuffer.size)
            if (readCount <= 0) continue

            val pcmChunk = if (readCount < shortBuffer.size) {
                shortBuffer.copyOf(readCount)
            } else {
                shortBuffer
            }

            val utteranceEnd: Boolean = try {
                voskEngine.acceptShort(pcmChunk)
            } catch (t: Throwable) {
                postError("Vosk accept failed: ${t.message}")
                return false
            }

            if (utteranceEnd) {
                val finalText = voskEngine.finalResult()
                postFinal(finalText)
                // Command utterance complete — return to outer loop.
                return true
            } else {
                val partial = voskEngine.partialResult()
                // Suppress hot-word detection from command-mode partials.
                if (!autoSwitchedToCommand || !containsHotWord(partial)) {
                    postPartial(partial)
                }
            }
        }

        // Stop requested while in command mode.
        return false
    }

    private fun createAudioRecord(): AudioRecord? {
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
            return null
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            postError("AudioRecord not initialised.")
            return null
        }

        audioRecord.startRecording()
        return audioRecord
    }

    // ── Hot-word detection ──────────────────────────────────────────────────

    /**
     * Check whether [text] contains the configured hot word.
     *
     * Performs a case-insensitive check on the raw partial result string.
     */
    private fun containsHotWord(text: String): Boolean {
        if (hotWord.isBlank()) return false
        return text.contains(hotWord, ignoreCase = true)
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

    private fun postHotWordDetected() {
        val listener = hotWordListener
        if (listener == null) return
        mainHandler.post {
            listener.onHotWordDetected()
        }
    }

    private fun postModeChange(newMode: VoskMode) {
        val listener = modeListener
        if (listener == null) return
        mainHandler.post {
            listener(newMode)
        }
    }

    private fun postError(message: String) {
        mainHandler.post {
            errorListener?.invoke(message)
        }
    }
}
