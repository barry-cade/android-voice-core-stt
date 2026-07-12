package dev.barrycade.voicecore.stt

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ModelManager owns the Whisper model lifecycle: load, unload, transcribe.
 *
 * Model load runs asynchronously on a dedicated single-thread executor
 * (triggered by [initAsync]). [isReady] reports true after load completes.
 *
 * Warm-up is handled by [SpeechToText] directly via [WhisperModel.warmup],
 * before the first PCM frame is processed.
 *
 * No PCM, VAD, STOP, AudioCapture, or lifecycle transitions.
 * Only Whisper model operations.
 */
internal class ModelManager(
    private val modelPath: String,
    private val sttErrorListener: SttErrorListener?,
    private var readyListener: SttReadyListener? = null,
    private val whisperModel: WhisperModel = WhisperBridge
) {
    /**
     * Dedicated single-thread executor for Whisper lifecycle operations.
     * All loadModel() and unloadModel() calls are serialized through this executor.
     */
    private val whisperExecutor: ExecutorService = Executors.newSingleThreadExecutor(
        ThreadFactory { runnable ->
            Thread(runnable, "WhisperExecutor").also { it.isDaemon = true }
        }
    )

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
     * Idempotency guard for executor shutdown.
     */
    private var executorsShutdown: Boolean = false

    /**
     * Testing hook: when true, initAsync() will fail fast with MODEL_LOAD_FAILED.
     */
    var forceWhisperLoadFailure: Boolean = false

    /**
     * Async model initialisation: load model + warm-up, then set isReady.
     *
     * After warm-up completes, [onReady] is invoked on the executor thread.
     * Use this to chain the start of the capture pipeline immediately after
     * model readiness, without queued-start flags or ready listener branching.
     *
     * Runs on whisperExecutor, never on the main/UI thread.
     */
    fun initAsync(onReady: () -> Unit = {}) {
        val runnable = Runnable {
            runInitSequence(onReady)
        }

        try {
            whisperExecutor.submit(runnable)
        } catch (_: RejectedExecutionException) {
            SttLogger.whisperE("initAsync: executor rejected task — may have been shut down")
        }
    }

    /**
     * Core init sequence executed on [whisperExecutor].
     * Loads model, runs warm-up, and sets [isReady] or [initFailed].
     * After completion, invokes [onReady] callback.
     */
    private fun runInitSequence(onReady: () -> Unit = {}) {
        try {
            if (handleForcedFailure()) return

            if (!loadModel()) return

            isReady = true
            SttLogger.lifecycle("ModelManager: model loaded, isReady=true")
            readyListener?.onSttReady()
            onReady()
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

    /**
     * If [forceWhisperLoadFailure] is set, report error and return true.
     * Otherwise return false.
     */
    private fun handleForcedFailure(): Boolean {
        if (!forceWhisperLoadFailure) return false

        SttLogger.error("forcedFailure: MODEL_LOAD_FAILED")
        val error = SttError(
            category = SttErrorCategory.UNKNOWN,
            code = SttErrorCode.MODEL_LOAD_FAILED,
            message = "Forced test failure: Whisper model load",
            context = mapOf("forcedFailure" to "forceWhisperLoadFailure")
        )
        sttErrorListener?.onSttError(error)
        initFailed = true
        return true
    }

    /**
     * Load the Whisper model. Returns true on success, false on failure.
     */
    private fun loadModel(): Boolean {
        SttLogger.whisper("loadModel: $modelPath")
        return try {
            whisperModel.loadModel(modelPath)
            true
        } catch (t: Throwable) {
            SttLogger.error("code=MODEL_LOAD_FAILED, message=\"${t.message}\"")
            initFailed = true
            false
        }
    }

    /**
     * Load the Whisper model if it is not already loaded.
     *
     * Synchronous, blocking call. Returns immediately if the model is
     * already loaded ([isReady] is true). Used by [SpeechToText.initStt]
     * to ensure model readiness before STT scaffolding is constructed.
     *
     * @return true if the model was loaded successfully or was already ready,
     *         false on failure.
     */
    fun loadModelIfNeeded(): Boolean {
        if (isReady) return true
        if (initFailed) return false

        return try {
            if (!loadModel()) return false
            isReady = true
            SttLogger.lifecycle("ModelManager: loadModelIfNeeded() — model loaded")
            true
        } catch (t: Throwable) {
            SttLogger.error("code=INIT_FAILED, message=\"${t.message}\"")
            initFailed = true
            false
        }
    }

    /**
     * Run warm-up inference synchronously on the current thread.
     *
     * Must be called after [loadModelIfNeeded]. Warm-up blocks until the
     * inference completes. Does NOT start PCM capture or STT processing.
     * Uses the Whisper model's warmup implementation.
     *
     * @param warmupDurationMs Duration of warm-up inference in ms.
     *        If <= 0, this method is a no-op.
     */
    fun runWarmup(warmupDurationMs: Int) {
        if (warmupDurationMs <= 0) return
        SttLogger.whisper("Warm-up: starting (${warmupDurationMs}ms)")
        whisperModel.warmup(warmupDurationMs)
        SttLogger.whisper("Warm-up: completed")
    }

    /**
     * Transcribe PCM samples by calling the native Whisper model.
     * Thread-safe: the C++ whisper mutex in whisper_bridge.cpp serialises
     * concurrent calls.
     *
     * @param pcm 16-bit linear PCM samples at 16 kHz mono.
     * @return Transcribed text (may be empty on silence or error).
     */
    fun transcribe(pcm: ShortArray): String {
        return whisperModel.transcribe(pcm)
    }

    /**
     * Submit an inference task to the whisper executor.
     *
     * The [onResult] callback is invoked on the whisper executor thread.
     * Callers must post to their own thread if main-thread delivery is required.
     *
     * Safe to call after [shutdown]; the task is silently discarded.
     */
    fun submitInference(pcm: ShortArray, onResult: (String) -> Unit) {
        val runnable = Runnable {
            val text = try {
                whisperModel.transcribe(pcm).trim()
            } catch (t: Throwable) {
                SttLogger.whisperE("inference failed: ${t.message}")
                return@Runnable
            }
            onResult(text)
        }
        try {
            whisperExecutor.submit(runnable)
        } catch (_: RejectedExecutionException) {
            SttLogger.whisperE("submitInference: executor rejected task — may have been shut down")
        }
    }

    /**
     * Unload the Whisper model. Resets warm-up flag.
     * Must be called before load to ensure deterministic lifecycle sequencing.
     */
    fun unload() {
        SttLogger.whisperD("Unloading model")
        whisperModel.unloadModel()
        isReady = false
    }

    /**
     * Shut down the whisper executor with a bounded timeout.
     * Idempotent: multiple calls are safe.
     */
    fun shutdown() {
        if (executorsShutdown) return
        executorsShutdown = true

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
}
