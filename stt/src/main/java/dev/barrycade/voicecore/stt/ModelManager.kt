package dev.barrycade.voicecore.stt

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ModelManager owns the Whisper model lifecycle: load, warm-up, unload, transcribe.
 * Warm-up runs asynchronously on a dedicated single-thread executor immediately
 * after model load. [isReady] reports true only after both load and warm-up complete.
 *
 * No PCM, VAD, STOP, AudioCapture, or lifecycle transitions.
 * Only Whisper model operations and the warm-up flag.
 */
internal class ModelManager(
    private val modelPath: String,
    private val sttErrorListener: SttErrorListener?,
    private var readyListener: SttReadyListener? = null
) {
    /**
     * Dedicated single-thread executor for Whisper lifecycle operations.
     * All loadModel() and unloadModel() calls are serialized through this executor.
     */
    private val whisperExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * True when model is loaded AND warm-up has completed.
     * Checked by LifecycleController before starting capture.
     */
    @Volatile
    var isReady: Boolean = false
        private set

    /**
     * True when model load or warm-up failed.
     * start() must check this and fail fast.
     */
    @Volatile
    var initFailed: Boolean = false
        private set

    /**
     * Tracks whether warm-up has been performed in this session.
     * Reset to false by [unload].
     */
    private var warmupPerformed: Boolean = false

    /**
     * Idempotency guard for executor shutdown.
     */
    private var executorsShutdown: Boolean = false

    /**
     * Cancellation flag for all Whisper executor tasks.
     */
    @Volatile
    private var whisperCancelled: Boolean = false

    /**
     * Testing hook: when true, initAsync() will fail fast with MODEL_LOAD_FAILED.
     */
    var forceWhisperLoadFailure: Boolean = false

    /**
     * Async model initialisation: load model + warm-up, then set isReady.
     * Runs on whisperExecutor, never on the main/UI thread.
     * LifecycleController must check [isReady] before starting capture.
     */
    fun initAsync() {
        whisperExecutor.submit {
            try {
                // ── Testing hook: forceWhisperLoadFailure ─────────────────
                if (forceWhisperLoadFailure) {
                    SttLogger.error("forcedFailure: MODEL_LOAD_FAILED")
                    val error = SttError(
                        category = SttErrorCategory.UNKNOWN,
                        code = SttErrorCode.MODEL_LOAD_FAILED,
                        message = "Forced test failure: Whisper model load",
                        context = mapOf("forcedFailure" to "forceWhisperLoadFailure")
                    )
                    sttErrorListener?.onSttError(error)
                    initFailed = true
                    return@submit
                }

                SttLogger.whisper("loadModel: $modelPath")
                WhisperBridge.loadModel(modelPath)

                performWarmup()

                isReady = true
                SttLogger.lifecycle("ModelManager: model loaded, warm-up complete, isReady=true")
                readyListener?.onSttReady()
            } catch (t: Throwable) {
                SttLogger.error("code=INIT_FAILED, message=\"${t.message}\"")
                val error = SttError(
                    category = SttErrorCategory.UNKNOWN,
                    code = SttErrorCode.MODEL_LOAD_FAILED,
                    message = "Model initialisation failed: ${t.message}",
                    cause = t,
                    context = mapOf("exception" to t::class.java.simpleName)
                )
                sttErrorListener?.onSttError(error)
                initFailed = true
            }
        }
    }

    /**
     * Perform Whisper warm-up on the whisperExecutor thread.
     * Runs after model load, before [isReady] is set to true.
     * Idempotent: runs exactly once per session until [unload] resets the flag.
     */
    private fun performWarmup() {
        if (warmupPerformed) return
        if (whisperCancelled) return

        val warmupStartMs = System.currentTimeMillis()
        try {
            WhisperBridge.transcribe(WARMUP_PCM)
            if (whisperCancelled) return
            val warmupMs = System.currentTimeMillis() - warmupStartMs
            SttLogger.whisper("warmUpMs=$warmupMs")
            warmupPerformed = true
        } catch (t: Throwable) {
            if (whisperCancelled) return
            SttLogger.whisperE("warmup failed: ${t.message}")
            val error = SttError(
                category = SttErrorCategory.UNKNOWN,
                code = SttErrorCode.INFERENCE_FAILED,
                message = "Whisper warm-up failed: ${t.message}",
                cause = t,
                context = mapOf("exception" to t::class.java.simpleName)
            )
            sttErrorListener?.onSttError(error)
            throw RuntimeException("Whisper warm-up failed", t)
        }
    }

    /**
     * Transcribe PCM samples. Thread-safe (C++ mutex in whisper_bridge.cpp).
     */
    fun transcribe(pcm: ShortArray): String {
        return WhisperBridge.transcribe(pcm)
    }

    /**
     * Unload the Whisper model. Resets warm-up flag.
     * Must be called before load to ensure deterministic lifecycle sequencing.
     */
    fun unload() {
        SttLogger.whisperD("Unloading model")
        WhisperBridge.unloadModel()
        warmupPerformed = false
        isReady = false
    }

    /**
     * Shut down the whisper executor with a bounded timeout.
     * Idempotent: multiple calls are safe.
     */
    fun shutdown() {
        if (executorsShutdown) return
        executorsShutdown = true

        whisperCancelled = true
        whisperExecutor.shutdown()
        try {
            val terminated = whisperExecutor.awaitTermination(5, TimeUnit.SECONDS)
            if (terminated) {
                SttLogger.pcm("[EXECUTOR] shutdown: whisperExecutor status=TERMINATED")
            } else {
                whisperExecutor.shutdownNow()
                SttLogger.pcm("[EXECUTOR] shutdown: whisperExecutor status=TIMEOUT")
            }
        } catch (e: InterruptedException) {
            whisperExecutor.shutdownNow()
            Thread.currentThread().interrupt()
            SttLogger.pcm("[EXECUTOR] shutdown: whisperExecutor status=INTERRUPTED")
        }
    }

    /**
     * Set or replace the READY listener.
     * If [isReady] is already true, the listener is invoked immediately.
     */
    fun setReadyListener(listener: SttReadyListener?) {
        readyListener = listener
        if (isReady) {
            readyListener?.onSttReady()
        }
    }

    companion object {
        /** Tiny PCM buffer for warm-up (200ms of silence at 16kHz). */
        private val WARMUP_PCM: ShortArray = ShortArray(3200)
    }
}
