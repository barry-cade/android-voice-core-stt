package dev.barrycade.voicecore.wuw

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Orchestrates wake-word listening, matching, and mode transitions.
 *
 * Owns the [AudioRecord] thread, [WakeWordEngine], [MfccExtractor], and
 * [TemplateStore]. On wake-word detection, it can either:
 * - Broadcast an intent (for loose coupling), or
 * - Fire a callback (for direct integration).
 *
 * Features a simple VAD-based auto-stop: if the microphone detects sustained
 * silence (RMS below threshold) for [silenceTimeoutMs] after any speech
 * activity, the session auto-stops and fires [silenceAutoStopListener]
 * with the peak similarity observed during the session.
 *
 * Lifecycle:
 * 1. Create with [Context] and optional configuration.
 * 2. Call [startListening] to begin capture.
 * 3. On detection, the engine fires [WakeWordListener.onWakeWordDetected].
 * 4. On silence timeout, [silenceAutoStopListener] fires with peak similarity.
 * 5. Call [stopListening] to end the session early.
 * 6. Call [destroy] to release all resources.
 */
class WakeWordSessionManager(
    private val context: Context,
    private val onDetectionAction: String? = null,
    threshold: Float = 0.7f,
    /** How long (ms) of sustained silence before auto-stopping. 0 = disabled. */
    private val silenceTimeoutMs: Long = 2000
) {
    /** Callback for wake-word detection events. Delivered on capture thread. */
    var wakeWordListener: WakeWordListener? = null

    /** Callback for every similarity score update. Delivered on capture thread. */
    var similarityListener: ((Float) -> Unit)? = null

    /**
     * Callback for PCM buffer snapshots for waveform visualization.
     * Delivered on the main thread.
     */
    var pcmListener: ((ShortArray) -> Unit)? = null

    var mfccListener: ((List<FloatArray>) -> Unit)? = null

    /**
     * Fired when the session auto-stops due to sustained silence after speech.
     * [peakSimilarity] is the highest similarity observed during the session.
     * Delivered on the main thread.
     */
    var silenceAutoStopListener: ((Float) -> Unit)? = null

    /** Callback for error events. Delivered on capture thread. */
    var errorListener: ((String) -> Unit)? = null

    /** True while the capture loop is running. */
    @Volatile
    var isListening: Boolean = false
        private set

    private val active = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var captureThread: Thread? = null

    /** VAD state: set to true when wake word detected or auto-stop fires. */
    private var hasFiredAutoStop: Boolean = false

    /** Peak similarity observed during the current session. */
    private var peakSimilarity: Float = 0f

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

        hasFiredAutoStop = false
        peakSimilarity = 0f
        wakeWordEngine.setTemplate(template)
        wakeWordEngine.setListener(createEngineListener())

        // Wrap the external similarity listener to track peak
        val externalSimilarityListener = similarityListener
        wakeWordEngine.similarityListener = { similarity ->
            if (similarity > peakSimilarity) {
                peakSimilarity = similarity
            }
            externalSimilarityListener?.invoke(similarity)
        }
        // Wire up MFCC frame forwarding
        wakeWordEngine.mfccFrameListener = { frames ->
            mainHandler.post {
                mfccListener?.invoke(frames)
            }
        }
        active.set(true)
        isListening = true

        val captureRunnable = Runnable { runCaptureLoop() }
        val thread = Thread(captureRunnable, "WuwCaptureThread")
        captureThread = thread
        thread.start()
    }

    /**
     * Stop listening for the wake word.
     */
    fun stopListening() {
        active.set(false)
        isListening = false
        wakeWordEngine.reset()
        captureThread?.interrupt()
    }

    /**
     * Release all resources held by this manager.
     */
    fun destroy() {
        stopListening()
        wakeWordEngine.destroy()
        wakeWordListener = null
        similarityListener = null
        errorListener = null
        silenceAutoStopListener = null
    }

    fun getEngine(): WakeWordEngine {
        return wakeWordEngine
    }

    fun getMfccExtractor(): MfccExtractor {
        return mfccExtractor
    }

    fun saveTemplate(pcm: ShortArray, name: String? = null): String {
        val mfccFrames = mfccExtractor.extract(pcm)
        if (mfccFrames.isEmpty()) return ""

        val templateName = name ?: "ww_${System.currentTimeMillis()}"
        val safeName = templateStore.uniqueName(templateName)
        templateStore.saveTemplate(safeName, mfccFrames)
        return safeName
    }

    fun setTemplateDirectly(mfccFrames: List<FloatArray>) {
        wakeWordEngine.setTemplate(mfccFrames)
    }

    fun hasTemplate(name: String): Boolean {
        return templateStore.hasTemplate(name)
    }

    fun deleteTemplate(name: String): Boolean {
        return templateStore.deleteTemplate(name)
    }

    fun listTemplates(): List<TemplateStore.TemplateInfo> {
        return templateStore.listTemplates()
    }

    fun setThreshold(value: Float) {
        wakeWordEngine.setThreshold(value)
    }

    // ── Internal capture loop ────────────────────────────────────────────────

    /**
     * Simple VAD: returns the RMS energy of a PCM buffer.
     * High RMS = speech present, low RMS = silence.
     */
    private fun computeRms(pcm: ShortArray): Float {
        if (pcm.isEmpty()) return 0f
        var sumSq = 0.0
        for (s in pcm) {
            val norm = s / 32768.0
            sumSq += norm * norm
        }
        return kotlin.math.sqrt(sumSq / pcm.size).toFloat()
    }

    private fun runCaptureLoop() {
        val audioRecord = createAudioRecord()
        if (audioRecord == null) {
            active.set(false)
            isListening = false
            return
        }

        val shortBuffer = ShortArray(bufferSizeSamples)

        // VAD state
        val useVad = silenceTimeoutMs > 0
        var lastSpeechTimeMs = System.currentTimeMillis()
        var hasEverHadSpeech = false

        val rmsThreshold = 0.015f  // RMS below this = silence (tune empirically)

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

            // Compute similarity from the engine (called inside processPcm)
            wakeWordEngine.processPcm(pcmChunk)

            // Grab PCM snapshot for waveform visualization
            val pcmlistener = pcmListener
            if (pcmlistener != null) {
                val snapshot = wakeWordEngine.getPcmSnapshot()
                if (snapshot.isNotEmpty()) {
                    mainHandler.post { pcmlistener(snapshot) }
                }
            }

            // VAD auto-stop logic
            if (useVad && !hasFiredAutoStop) {
                val rms = computeRms(pcmChunk)
                val now = System.currentTimeMillis()

                if (rms >= rmsThreshold) {
                    // Speech detected
                    hasEverHadSpeech = true
                    lastSpeechTimeMs = now
                }

                if (hasEverHadSpeech && (now - lastSpeechTimeMs) >= silenceTimeoutMs) {
                    // Sustained silence after speech — auto-stop
                    hasFiredAutoStop = true
                    active.set(false)
                    isListening = false

                    val peak = peakSimilarity
                    mainHandler.post {
                        silenceAutoStopListener?.invoke(peak)
                    }
                    break
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
                // Wake word detected — don't auto-stop
                hasFiredAutoStop = true

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