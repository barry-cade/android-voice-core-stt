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

    /** Callback for every similarity score update. Delivered on capture thread. */
    var similarityListener: ((Float) -> Unit)? = null

    /**
     * Callback for PCM buffer snapshots for waveform visualization.
     * Delivered on the capture thread. Receives a copy of the engine's
     * current PCNeM sliding window.
     */
    var pcmListener: ((ShortArray) -> Unit)? = null

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
     * Loads the stored template by name, starts the capture thread,
     * and feeds PCM frames into the engine for continuous matching.
     *
     * @param templateName Name of the template to use. If null,
     *                     loads the first available template.
     */
    fun startListening(templateName: String? = null) {
        if (active.get()) {
            throw IllegalStateException("Wake word session already active")
        }

        val template = if (templateName != null) {
            templateStore.loadTemplate(templateName)
        } else {
            val templates = templateStore.listTemplates()
            if (templates.isEmpty()) emptyList()
            else templateStore.loadTemplate(templates.first().name)
        }

        if (template.isEmpty()) {
            val listener = errorListener
            if (listener != null) {
                listener("No wake-word template found. Record one first.")
            }
            return
        }

        wakeWordEngine.setTemplate(template)
        wakeWordEngine.setListener(createEngineListener())
        wakeWordEngine.similarityListener = similarityListener
        active.set(true)
        isListening = true

        val captureRunnable = Runnable { runCaptureLoop() }
        val thread = Thread(captureRunnable, "WuwCaptureThread")
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
        captureThread?.interrupt()
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
        similarityListener = null
        errorListener = null
    }

    /**
     * Get the underlying [WakeWordEngine] for parameter tuning (calibration).
     * The engine is live while listening; field changes take effect immediately.
     */
    fun getEngine(): WakeWordEngine {
        return wakeWordEngine
    }

    /**
     * Get the underlying [MfccExtractor] for parameter tuning (calibration).
     * All configuration is set at construction time.
     */
    fun getMfccExtractor(): MfccExtractor {
        return mfccExtractor
    }

    /**
     * Save a PCM recording as a named wake-word template.
     *
     * Extracts MFCC from the PCM and saves via [TemplateStore].
     *
     * @param pcm PCM samples (16 kHz, mono, 16-bit).
     * @param name Template name. If null, generated from the current timestamp.
     * @return The name the template was saved under.
     */
    fun saveTemplate(pcm: ShortArray, name: String? = null): String {
        val mfccFrames = mfccExtractor.extract(pcm)
        if (mfccFrames.isEmpty()) return ""

        val templateName = name ?: "ww_${System.currentTimeMillis()}"
        val safeName = templateStore.uniqueName(templateName)
        templateStore.saveTemplate(safeName, mfccFrames)
        return safeName
    }

    /**
     * Set a template on the engine directly (from pre-extracted MFCC frames).
     * Does NOT persist to storage. Used when the caller has already loaded
     * a template and wants to inject it without a save/load cycle.
     */
    fun setTemplateDirectly(mfccFrames: List<FloatArray>) {
        wakeWordEngine.setTemplate(mfccFrames)
    }

    /**
     * Check whether any template exists with the given name.
     */
    fun hasTemplate(name: String): Boolean {
        return templateStore.hasTemplate(name)
    }

    /**
     * Delete a named template.
     */
    fun deleteTemplate(name: String): Boolean {
        return templateStore.deleteTemplate(name)
    }

    /**
     * List all saved templates.
     */
    fun listTemplates(): List<TemplateStore.TemplateInfo> {
        return templateStore.listTemplates()
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
            if (Thread.currentThread().isInterrupted) {
                break
            }

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

            // Grab PCM snapshot for waveform visualization (every chunk)
            val pcmlistener = pcmListener
            if (pcmlistener != null) {
                val snapshot = wakeWordEngine.getPcmSnapshot()
                if (snapshot.isNotEmpty()) {
                    mainHandler.post { pcmlistener(snapshot) }
                }
            }
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
        val detectionAction = onDetectionAction

        return object : WakeWordListener {
            override fun onWakeWordDetected() {
                mainHandler.post {
                    wakeWordListener?.onWakeWordDetected()
                }

                if (detectionAction != null) {
                    val intent = Intent(detectionAction)
                    context.sendBroadcast(intent)
                }
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
