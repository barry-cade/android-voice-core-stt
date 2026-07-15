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
    private var modelPath: String,
    private val sttErrorListener: SttErrorListener?,
    private val whisperModel: WhisperModel = WhisperBridge
) {
    private val stateLock = Any()

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
    private val executorsShutdown = AtomicBoolean(false)

    /**
     * Testing hook: when true, initAsync() will fail fast with MODEL_LOAD_FAILED.
     */
    @Volatile
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
            onReady()
        } catch (t: Throwable) {
            SttLogger.error("code=INIT_FAILED, message=\"${t.message}\"")
            val error = SttError(
                code = SttErrorCode.MODEL_LOAD_FAILED,
                message = "Model initialisation failed: ${t.message}",
                cause = t,
                details = listOf("exception=${t::class.java.simpleName}")
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
            code = SttErrorCode.MODEL_LOAD_FAILED,
            message = "Forced test failure: Whisper model load",
            details = listOf("forcedFailure=forceWhisperLoadFailure")
        )
        sttErrorListener?.onSttError(error)
        initFailed = true
        return true
    }

    /**
     * Load the Whisper model. Returns true on success, false on failure.
     */
    private fun loadModel(): Boolean {
        val modelPathSnapshot = synchronized(stateLock) {
            modelPath
        }
        SttLogger.whisper("loadModel: $modelPathSnapshot")
        return try {
            whisperModel.loadModel(modelPathSnapshot)
            true
        } catch (t: Throwable) {
            SttLogger.error("code=MODEL_LOAD_FAILED, message=\"${t.message}\"")
            initFailed = true
            false
        }
    }

    /**
     * Update the model path. Called from [SpeechToText.initStt] when the
     * singleton [SpeechToText] is first initialised with a [SttConfig]
     * that specifies the actual model binary path.
     *
     * Safe to call before the model is loaded. No-op after the model has
     * been loaded (path is frozen).
     */
    fun updateModelPath(path: String) {
        synchronized(stateLock) {
            if (isReady || initFailed) return
            modelPath = path
        }
    }

    /**
     * Load the Whisper model if it is not already loaded.
     *
     * Synchronous, blocking call. Returns immediately if the model is

     * already loaded ([isReady] is true). Used by [SpeechToText.init]
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
     */
    fun runWarmup(warmupDurationMs: Int) {
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
     *
     * @return true if the task was accepted by the executor, false otherwise.
     */
    fun submitInference(
        pcm: ShortArray,
        onResult: (inferenceStartMs: Long, text: String) -> Unit,
        onComplete: () -> Unit = {}
    ): Boolean {
        val runnable = Runnable {
            try {
                val inferenceStartMs = System.currentTimeMillis()
                val text = whisperModel.transcribe(pcm).trim()
                onResult(inferenceStartMs, text)
            } catch (t: Throwable) {
                SttLogger.whisperE("inference failed: ${t.message}")
            } finally {
                onComplete()
            }
        }
        try {
            whisperExecutor.submit(runnable)
            return true
        } catch (_: RejectedExecutionException) {
            SttLogger.whisperE("submitInference: executor rejected task — may have been shut down")
            onComplete()
            return false
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
        if (!executorsShutdown.compareAndSet(false, true)) return

        whisperExecutor.shutdown()
        try {
            val terminated = whisperExecutor.awaitTermination(5, TimeUnit.SECONDS)
            if (terminated) {
                SttLogger.pcm("shutdown: whisperExecutor status=TERMINATED")
            } else {
                whisperExecutor.shutdownNow()
                SttLogger.pcm("shutdown: whisperExecutor status=TIMEOUT")
            }
        } catch (e: InterruptedException) {
            whisperExecutor.shutdownNow()
            Thread.currentThread().interrupt()
            SttLogger.pcm("shutdown: whisperExecutor status=INTERRUPTED")
        }
    }

}
