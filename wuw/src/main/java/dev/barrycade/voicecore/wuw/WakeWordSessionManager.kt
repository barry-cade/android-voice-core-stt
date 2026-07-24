package dev.barrycade.voicecore.wuw

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates wake-word listening, matching, and mode transitions.
 *
 * Owns the [AudioRecord] thread, [WakeWordEngine], [MfccExtractor], and
 * [TemplateStore]. On wake-word detection, it can either:
 * - Broadcast an intent (for loose coupling), or
 * - Fire a callback (for direct integration).
 *
 * Lifecycle:
 * 1. Create with [Context] and optional configuration.
 * 2. Call [startListening] to begin capture.
 * 3. On detection, the engine fires [WakeWordListener.onWakeWordDetected].
 * 4. Call [stopListening] to end the session.
 * 5. Call [destroy] to release all resources.
 */
class WakeWordSessionManager(
    private val context: Context,
    /** Optional intent action to broadcast on wake-word detection. */
    private val onDetectionAction: String? = null,
    /** Similarity threshold passed to the engine. */
    threshold: Float = 0.7f
) {
    /** Callback for wake-word detection events. Delivered on capture thread. */
    var wakeWordListener: WakeWordListener? = null

    /**
     * Callback for error events. Delivered on capture thread.
     * Receives a human-readable description.
     */
    var errorListener: ((String) -> Unit)? = null

    /** True while the capture loop is running. */
    @Volatile
    var isListening: Boolean = false
        private set

    private val active = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var captureThread: Thread? = null

    private val mfccExtractor = MfccExtractor()
    private val templateStore = TemplateStore(context)
    private val wakeWordEngine = WakeWordEngine(mfccExtractor)

    /** Audio capture parameters. */
    private val sampleRate: Int = 16000
    private val bufferSizeSamples: Int = 4000

    init {
        wakeWordEngine.setThreshold(threshold)
    }

    /**
     * Start listening for the wake word.
     *
     * Loads the stored template (if available), starts the capture thread,
     * and feeds PCM frames into the engine for continuous matching.
     *
     * @throws IllegalStateException if already listening or template not found.
     */
    fun startListening() {
        if (active.get()) {
            throw IllegalStateException("Wake word session already active")
        }

        // Load template from storage.
        val template = templateStore.loadTemplate()
        if (template.isEmpty()) {
            val listener = errorListener
            if (listener != null) {
                listener("No wake-word template found. Record one first.")
            }
            return
        }

        wakeWordEngine.setTemplate(template)
        wakeWordEngine.setListener(createEngineListener())
        active.set(true)
        isListening = true

        val thread = Thread({
            runCaptureLoop()
        }, "WuwCaptureThread")

        captureThread = thread
        thread.start()
    }

    /**
     * Stop listening for the wake word.
     *
     * Safe to call when not listening (no-op).
     */
    fun stopListening() {
        active.set(false)
        isListening = false
        wakeWordEngine.reset()
    }

    /**
     * Release all resources held by this manager.
     *
     * Stops listening, clears the engine, and nulls out references.
     * After calling this, the manager must not be reused.
     */
    fun destroy() {
        stopListening()
        wakeWordEngine.destroy()
        wakeWordListener = null
        errorListener = null
    }

    /**
     * Save a PCM recording as the wake-word template.
     *
     * Convenience method that extracts MFCC from the PCM and saves
     * via [TemplateStore].
     *
     * @param pcm PCM samples (16 kHz, mono, 16-bit).
     */
    fun saveTemplate(pcm: ShortArray) {
        val mfccFrames = mfccExtractor.extract(pcm)
        if (mfccFrames.isEmpty()) {
            return
        }
        templateStore.saveTemplate(mfccFrames)
    }

    /**
     * Check whether a stored template exists.
     */
    fun hasTemplate(): Boolean {
        return templateStore.hasTemplate()
    }

    /**
     * Delete any stored template.
     */
    fun deleteTemplate() {
        templateStore.deleteTemplate()
    }

    /**
     * Set the similarity threshold on the engine.
     *
     * @param value Threshold in [0, 1]. Higher = stricter.
     */
    fun setThreshold(value: Float) {
        wakeWordEngine.setThreshold(value)
    }

    // ── Internal capture loop ────────────────────────────────────────────────

    private fun runCaptureLoop() {
        val audioRecord = createAudioRecord()

        if (audioRecord == null) {
            active.set(false)
            isListening = false
            return
        }

        val shortBuffer = ShortArray(bufferSizeSamples)

        while (active.get()) {
            val readCount = audioRecord.read(shortBuffer, 0, shortBuffer.size)

            if (readCount <= 0) {
                continue
            }

            val pcmChunk = if (readCount < shortBuffer.size) {
                shortBuffer.copyOf(readCount)
            } else {
                shortBuffer
            }

            wakeWordEngine.processPcm(pcmChunk)
        }

        audioRecord.stop()
        audioRecord.release()
        isListening = false
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

    private fun createEngineListener(): WakeWordListener {
        return WakeWordListener {
            // Dispatch to main handler.
            mainHandler.post {
                wakeWordListener?.onWakeWordDetected()
            }

            // Broadcast action if configured.
            val action = onDetectionAction
            if (action != null) {
                val intent = Intent(action)
                context.sendBroadcast(intent)
            }
        }
    }

    private fun postError(message: String) {
        val listener = errorListener
        if (listener == null) {
            return
        }
        mainHandler.post {
            listener(message)
        }
    }
}
