package dev.barrycade.voicecore.stt

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Main entry point for the STT pipeline.
 *
 * ## Lifecycle
 *
 * 1. Call [loadModel] with a JSON config string to load the model and configure the pipeline.
 *    Safe to call at app startup — model loading and warmup happen without starting capture.
 * 2. Call [startSession] to begin audio capture and start a transcription session.
 *    Returns immediately since the model is already loaded.
 * 3. Call [transcribe] to stop the current utterance and transcribe it.
 * 4. Results and errors arrive via the listener registered with [setOnMessageListener].
 *
 * For backward compatibility, [init] performs both [loadModel] and [startSession]
 * in a single call. Prefer the two-phase API for responsive UI startup.
 *
 * ## Threading and lock model
 *
 * | Thread | Owns | Notes |
 * |--------|------|-------|
 * | Caller thread | Public lifecycle methods ([loadModel], [startSession], [init], [transcribe]) | Serialized via [stateLock] |
 * | Audio capture (T1) | [AudioRecord] reads, PCM frame enqueue | Guarded by [AudioCapture.stateLock] for start/stop |
 * | Capture drain (T2) | Warm-up PCM buffering into session buffer | Guarded by [CaptureManager.sessionBufferLock] |
 * | Processor (T3) | VAD, utterance accumulation, PCM polling | Started/stopped via [ProcessorController] |
 * | Whisper executor (T4) | Model load/unload/transcribe | Single-thread executor in [ModelManager] |
 *
 * ### Lock boundaries
 *
 * - [stateLock] guards ALL public lifecycle methods end-to-end.
 * - [stateLock] is NOT held across blocking operations (thread joins,
 *   native inference, executor shutdown).
 * - Blocking operations (thread joins, native unload) are performed
 *   OUTSIDE [stateLock] — they happen before or after the lock scope.
 *
 * ### Stale callback rejection
 *
 * - [sessionEpoch] is an [AtomicLong] incremented on each session start.
 * - [currentSessionEpoch] is snapshotted at inference submission.
 * - Callbacks whose epoch does not match [currentSessionEpoch] are dropped.
 *
 * ### JSON message callback
 *
 * The JSON message callback is **not** delivered on the main thread.
 * Callers must post to their own [android.os.Handler] or
 * [kotlinx.coroutines.Dispatchers.Main] if main-thread delivery is required.
 *
 * All lifecycle methods ([loadModel], [startSession], [init], [transcribe]) are
 * serialized internally via [stateLock].
 *
 * Callers MUST NOT call lifecycle methods from within callbacks — doing so
 * will produce undefined behavior (potential deadlock or re-entrancy).
 */
class SpeechToText internal constructor(
    @Suppress("UNUSED_PARAMETER") context: Context?,
    private val whisperModel: WhisperModel = WhisperBridge,
    private val captureManager: SessionManager = CaptureManager()
) {
    // ── Controller references ────────────────────────────────────────────

    /** Capture lifecycle controller, wraps the [SessionManager]. */
    private var captureController: SttCaptureController = SttCaptureController(captureManager)

    internal val lifecycleController = SttLifecycleController()
    private val sessionController = SttSessionController()
    private val modeController = SttModeController()
    private val threadController = SttThreadController()
    private val callbackDispatcher = SttCallbackDispatcher()

    // ── Test options ─────────────────────────────────────────────────────

    /**
     * When true, AudioCapture initialisation will fail.
     * Set via debug options.
     */
    private var forceAudioInitFailure: Boolean = false

    /**
     * When true, UtteranceAccumulator will force a timeout.
     * Read at [init] time.
     */
    private var forceAccumulatorTimeout: Boolean = false

    /**
     * Internal timing listener, wired to callback dispatcher.
     */
    internal var onTimingListener: ((pcmMs: Long, vadActiveMs: Long, whisperMs: Long, totalMs: Long) -> Unit)? = null
        set(value) {
            field = value
            callbackDispatcher.onTimingListener = value
        }

    /** Thread-safe running/inference flags. */
    private val isRunning = AtomicBoolean(false)
    private val isInferencing = AtomicBoolean(false)

    /** Monotonic session epoch for stale-callback rejection. */
    private val sessionEpoch = AtomicLong(0L)

    /** Current active session epoch. 0 means no active session. */
    @Volatile
    private var currentSessionEpoch: Long = 0L

    /** Synchronisation lock for state transitions. */
    private val stateLock = Any()

    /** Deterministic pipeline stage holder for runtime flow control. */
    private val pipelineState = SttPipelineState()

    /** Model manager — created once, persists across utterances. */
    private val modelManager: ModelManager

    /** Dedicated inference adapter controller. */
    private val inferenceController: SttInferenceController

    /** Processing controller for Auto mode. null in Manual mode. */
    private var processingController: SttProcessingController? = null

    /**
     * VAD instance for strategy evaluation.
     * null until initialised (Manual mode never initialises it).
     * Set during [initStt] alongside [processingController].
     */
    private var vad: Vad? = null

    /**
     * Immutable session config, built during [init].
     * null until [init] completes successfully.
     */
    private var sessionConfig: SttSessionConfig? = null

    /** Observable events for start/stop strategies. */
    private val events: SttEvents = SttEvents()

    /**
     * Single-owner stop signal. Written only in [transcribe],
     * cleared on session reset and teardown.
     */
    private val stopRequest = StopRequest()

    init {
        modelManager = ModelManager(
            modelPath = "",
            sttErrorListener = null,
            whisperModel = whisperModel
        )
        inferenceController = SttInferenceController(modelManager, callbackDispatcher)
        lifecycleController.onInit()
        SttLogger.lifecycle("SpeechToText constructed — model NOT loaded. Call init() to initialise.")
    }

    // ------- Public JSON API ------------------------------------------

    /**
     * Load the STT model and configure the pipeline without starting capture.
     *
     * Parses the JSON config, loads the model, runs warm-up (if configured),
     * and builds internal scaffolding. Does NOT start audio capture or begin
     * a session — call [startSession] to do that.
     *
     * Safe to call at app startup. Idempotent — subsequent calls return
     * immediately once the model is loaded.
     *
     * @param configJson A JSON string conforming to the input config schema.
     *                   See [SttJsonAdapter.parseConfig] for the expected shape.
     * @return A JSON result string on success, or a JSON error string on failure.
     */
    fun loadModel(configJson: String): String {
        val sttConfig = try {
            SttJsonAdapter.parseConfig(configJson)
        } catch (e: IllegalArgumentException) {
            return SttJsonAdapter.buildErrorJson("INVALID_CONFIG", e.message ?: "Config parse failed")
        }

        synchronized(stateLock) {
            // ── Idempotency guard ─────────────────────────────────────────────
            if (sessionConfig != null || lifecycleController.currentState is SttLifecycleState.READY) {
                SttLogger.lifecycle("loadModel: already initialised — returning SUCCESS")
                return SttJsonAdapter.buildResultJson("", SttReturnCode.SUCCESS, null)
            }

            // ── Step 1: Build immutable session config ────────────────────────
            val sessionCfg = SttSessionConfig.from(sttConfig)
            sessionConfig = sessionCfg
            val runtimeCfg = sessionCfg.runtimeConfig

            // ── Step 2: Update model path and load model ──────────────────────
            modelManager.updateModelPath(sessionCfg.modelPath)
            if (!modelManager.loadModelIfNeeded()) {
                sessionConfig = null
                return SttJsonAdapter.buildErrorJson("INIT_FAILED", "Model load failed")
            }

            // ── Step 3: Warm-up (once per app lifetime) ───────────────────────
            if (sessionCfg.warmupEnabled) {
                modelManager.runWarmup(sessionCfg.warmupDurationMs)
            }

            // ── Step 4: Reconstruct CaptureManager with runtime buffer size ───
            val sessionManager: SessionManager
            if (captureController.sessionManager is CaptureManager) {
                val newManager = CaptureManager(
                    bufferSizeSamples = sessionCfg.bufferSizeSamples
                )
                captureController = SttCaptureController(newManager)
                sessionManager = newManager
            } else {
                sessionManager = captureController.sessionManager
            }

            // ── Step 5: Construct STT scaffolding via mode controller ─────────
            modeController.selectController(
                config = runtimeCfg,
                captureManager = sessionManager,
                stopRequestedRef = stopRequest.asSupplier()
            )

            if (!modeController.isManualMode()) {
                val procController = SttProcessingController(
                    config = runtimeCfg,
                    captureManager = sessionManager,
                    stopRequestedRef = stopRequest.asSupplier(),
                    sttErrorListener = callbackDispatcher.getSttErrorListener(),
                    forceTimeout = forceAccumulatorTimeout,
                    listener = ProcessingListener { pcm, code ->
                        handleUtteranceReady(pcm, code)
                    }
                )
                processingController = procController
                vad = procController.vad
            }

            // ── Step 6: Mark ready ────────────────────────────────────────────
            lifecycleController.onReady()
            SttLogger.lifecycle("loadModel: model loaded, pipeline configured — call startSession() to begin capture")

            return SttJsonAdapter.buildResultJson("", SttReturnCode.SUCCESS, null)
        }
    }

    /**
     * Start a capture session.
     *
     * Begins audio capture, starts the processor, and transitions to
     * the CAPTURING pipeline stage. Must be called after [loadModel]
     * has completed successfully.
     *
     * Idempotent — returns immediately if a session is already active.
     *
     * @return A JSON result string on success, or a JSON error string
     *         on failure (e.g. model not loaded).
     */
    fun startSession(): String {
        synchronized(stateLock) {
            // ── Guard: must have loaded a model ───────────────────────────────
            val cfg = sessionConfig
            if (cfg == null) {
                return SttJsonAdapter.buildErrorJson(
                    "SESSION_FAILED",
                    "loadModel() must be called before startSession()"
                )
            }

            val runtimeCfg = cfg.runtimeConfig

            if (!modelManager.isReady) {
                return SttJsonAdapter.buildErrorJson("SESSION_FAILED", "Model not ready")
            }
            if (isInferencing.get()) {
                return SttJsonAdapter.buildErrorJson("SESSION_FAILED", "Inference already active")
            }
            if (!lifecycleController.canStartSession()) {
                // If already in a session (RECORDING or CAPTURING), return success
                if (lifecycleController.isRecording() || pipelineState.currentStage == SttPipelineStage.CAPTURING) {
                    return SttJsonAdapter.buildResultJson("", SttReturnCode.SUCCESS, null)
                }
                return SttJsonAdapter.buildErrorJson(
                    "SESSION_FAILED",
                    "Cannot start session from state ${lifecycleController.currentState}"
                )
            }

            events.manualStartPressed.raise()
            if (!runtimeCfg.startStrategy.shouldStart(events, vad)) {
                return SttJsonAdapter.buildResultJson("", SttReturnCode.SUCCESS, null)
            }

            currentSessionEpoch = sessionEpoch.incrementAndGet()
            if (!transitionPipelineStageLocked(SttPipelineStage.CAPTURING, "startSession")) {
                return SttJsonAdapter.buildErrorJson("SESSION_FAILED", "Pipeline stage transition failed")
            }
            sessionController.beginSession()
            captureController.startCapture(modeController.isManualMode())

            if (modeController.isManualMode()) {
                captureController.activatePcmCapture()
                sessionController.beginUtteranceTiming()
                modeController.minimalProcessorController?.start()
            } else {
                startProcessor()
            }

            SttLogger.lifecycle("startSession: capture started")
            return SttJsonAdapter.buildResultJson("", SttReturnCode.SUCCESS, null)
        }
    }

    /**
     * Initialise STT and start a session in a single call.
     *
     * Convenience method equivalent to calling [loadModel] then [startSession].
     * For responsive UI startup, prefer the two-phase API:
     * call [loadModel] at app startup and [startSession] on the Start button.
     *
     * @param configJson A JSON string conforming to the input config schema.
     *                   See [SttJsonAdapter.parseConfig] for the expected shape.
     * @return A JSON result string on success, or a JSON error string on failure.
     */
    fun init(configJson: String): String {
        val loadResult = loadModel(configJson)
        if (hasJsonError(loadResult)) {
            return loadResult
        }
        return startSession()
    }

    /**
     * Transcribe the current utterance and deliver the result via the message listener.
     *
     * Stops the current capture session, runs inference on accumulated PCM,
     * and dispatches the result via [setOnMessageListener].
     *
     * The result JSON will have `"type": "result"` on success, or
     * `"type": "error"` if an error occurs.
     */
    fun transcribe() {
        synchronized(stateLock) {
            val cfg = sessionConfig
            if (cfg == null) return

            val runtimeCfg = cfg.runtimeConfig
            val elapsedMs = sessionController.endSession().toInt()

            SttLogger.pcm("entered -- isRunning=${isRunning.get()}, state=${lifecycleController.currentState}")

            events.manualStopPressed.raise()

            if (!runtimeCfg.stopStrategy.shouldStop(events, vad, elapsedMs)) {
                return
            }

            if (lifecycleController.currentState is SttLifecycleState.STOPPED) {
                SttLogger.pcm("ignoring -- state=STOPPED")
                return
            }

            if (lifecycleController.currentState is SttLifecycleState.FINALISING) {
                SttLogger.pcm("already FINALISING -- returning")
                return
            }

            if (!transitionPipelineStageLocked(SttPipelineStage.FINALISING, "transcribe")) {
                SttLogger.lifecycleW("transcribe() ignored -- illegal stage from ${pipelineState.currentStage}")
                return
            }

            isRunning.set(false)
            stopRequest.raise()
            modeController.stopController()

            val finalPcm = captureController.finaliseAndStop()

            if (finalPcm.isEmpty()) {
                SttLogger.pcm("no PCM accumulated -- transitioning to STOPPED then READY")
                transitionPipelineToIdleLocked("transcribe empty pcm")
                currentSessionEpoch = 0L
                lifecycleController.onStop()
                lifecycleController.onReset()
                return
            }

            lifecycleController.onFinalising()
            val timingMs = sessionController.currentPcmElapsedMs()

            val epoch = currentSessionEpoch
            if (epoch == 0L) {
                SttLogger.lifecycleW("transcribe() with no active session epoch -- dropping inference")
                transitionPipelineToIdleLocked("transcribe missing epoch")
                lifecycleController.onStop()
                lifecycleController.onReset()
                return
            }

            if (!enterInferencingLocked("transcribe")) {
                SttLogger.lifecycleW("transcribe() ignored -- inference already active")
                return
            }

            val vadMs = processingController?.vadActiveMs ?: 0L
            val utterMs = (processingController?.lastUtteranceDurationMs ?: 0).toLong()
            val submitted = submitInferenceAndDispatch(
                pcm = finalPcm,
                code = SttReturnCode.SUCCESS,
                vadActiveMs = vadMs,
                utteranceMs = utterMs,
                captureMs = timingMs,
                sessionEpochAtSubmission = epoch,
                completeStopPath = true
            )

            if (!submitted) {
                SttLogger.lifecycleW("transcribe() -- inference submission failed")
            }
        }
    }

    /**
     * Unified JSON message listener.
     *
     * Receives all STT output as JSON strings. The caller should inspect the
     * `"type"` field to distinguish:
     * - `"result"` — successful transcription
     * - `"error"` — an error occurred
     *
     * **Delivery thread:** Varies (Whisper executor for results, error thread for errors).
     * Post to your own Handler or coroutine dispatcher if main-thread delivery is required.
     */
    fun setOnMessageListener(l: (String) -> Unit) {
        callbackDispatcher.setOnMessageListener(l)
    }

    // ======== Internal pipeline ========================================

    /**
     * Test helper: bypass the start strategy and trigger start directly.
     */
    internal fun processStart() {
        synchronized(stateLock) {
            if (!lifecycleController.canStartSession()) return@synchronized
            startProcessor()
        }
    }

    /**
     * Start the processor for the current session.
     * Must be called from within [stateLock].
     */
    private fun startProcessor() {
        if (isRunning.get()) return

        if (!modelManager.isReady) {
            SttLogger.lifecycleW("startProcessor() called before model ready -- ignoring")
            return
        }
        if (modelManager.initFailed) {
            callbackDispatcher.dispatchError(RuntimeException("Model initialisation failed"))
            return
        }
        if (forceAudioInitFailure) {
            callbackDispatcher.dispatchError(RuntimeException("Forced test: AudioCapture init"))
            return
        }
        if (!lifecycleController.canStartSession()) {
            SttLogger.lifecycleW("startProcessor() called from ${lifecycleController.currentState} -- ignoring")
            return
        }

        sessionController.resetUtteranceTiming()
        val cfg = sessionConfig
        if (cfg != null) {
            SttLogger.config("Active config: ${cfg.runtimeConfig}")
        }

        if (!lifecycleController.onStart()) {
            return
        }

        sessionController.beginPcmTiming()

        val controller = if (modeController.isManualMode()) {
            modeController.selectedController()
        } else {
            processingController?.processorController
        }

        if (controller == null) {
            SttLogger.error("code=INTEGRATION_ERROR, message=\"startProcessor(): controller is null — call init() first\"")
            return
        }

        isRunning.set(true)
        controller.start()
        sessionController.beginUtteranceTiming()
    }

    // ── Session-scoped state ─────────────────────────────────────────────

    // ======== Inference and dispatch ======================================

    /**
     * Handle utterance-ready event from the mode controller's utterance handler.
     */
    private fun handleUtteranceReady(pcm: FloatArray, code: SttReturnCode) {
        if (!isRunning.get()) return
        val epoch = currentSessionEpoch
        if (epoch == 0L) return

        synchronized(stateLock) {
            if (!enterInferencingLocked("handleUtteranceReady")) {
                return
            }
        }

        try {
            val vadMs = processingController?.vadActiveMs ?: 0L
            val utterMs = (processingController?.lastUtteranceDurationMs ?: 0).toLong()
            val captureMs = sessionController.captureMs()
            val submitted = submitInferenceAndDispatch(
                pcm = pcm,
                code = code,
                vadActiveMs = vadMs,
                utteranceMs = utterMs,
                captureMs = captureMs,
                sessionEpochAtSubmission = epoch,
                completeStopPath = false
            )
            if (!submitted) {
                handleInferenceRejected()
            }
        } catch (t: Throwable) {
            handleInferenceError(t)
        }
    }

    /**
     * Submit an inference task to the whisper executor.
     */
    private fun submitInferenceAndDispatch(
        pcm: FloatArray,
        code: SttReturnCode,
        vadActiveMs: Long,
        utteranceMs: Long,
        captureMs: Long,
        sessionEpochAtSubmission: Long,
        completeStopPath: Boolean
    ): Boolean {
        val cfg = sessionConfig
        if (cfg == null) return false

        val runtimeCfg = cfg.runtimeConfig
        val request = SttInferenceController.InferenceRequest(
            pcm = pcm,
            code = code,
            vadActiveMs = vadActiveMs,
            utteranceMs = utteranceMs,
            captureMs = captureMs,
            preRollMs = runtimeCfg.preRollMs.toLong(),
            autoSilenceMs = runtimeCfg.autoSilenceMs.toLong(),
            pipelineStartMs = sessionController.utteranceStartMs(),
            sessionEpochAtSubmission = sessionEpochAtSubmission
        )

        val decideDispatch = createDecideDispatch(sessionEpochAtSubmission)
        val onPostDispatch = createOnPostDispatch(sessionEpochAtSubmission, completeStopPath)
        val onComplete = createOnComplete(sessionEpochAtSubmission, completeStopPath)

        return inferenceController.submit(
            request = request,
            decideDispatch = decideDispatch,
            onPostDispatch = onPostDispatch,
            onComplete = onComplete
        )
    }

    private fun createDecideDispatch(sessionEpochAtSubmission: Long): () -> SttInferenceController.DispatchDecision {
        return {
            synchronized(stateLock) {
                if (sessionEpochAtSubmission != currentSessionEpoch) {
                    SttInferenceController.DispatchDecision(
                        shouldDispatch = false,
                        dropReason = "stale submissionEpoch=$sessionEpochAtSubmission currentEpoch=$currentSessionEpoch"
                    )
                } else {
                    val transitioned = transitionPipelineStageLocked(
                        SttPipelineStage.DISPATCHING,
                        "inference result ready"
                    )
                    if (transitioned) {
                        SttInferenceController.DispatchDecision(shouldDispatch = true)
                    } else {
                        SttInferenceController.DispatchDecision(
                            shouldDispatch = false,
                            dropReason = "illegal stage transition to DISPATCHING from ${pipelineState.currentStage}"
                        )
                    }
                }
                }
        }
    }

    private fun createOnPostDispatch(sessionEpochAtSubmission: Long, completeStopPath: Boolean): () -> Unit {
        if (completeStopPath) {
            return {}
        }
        return {
            synchronized(stateLock) {
                if (sessionEpochAtSubmission != currentSessionEpoch) {
                    transitionPipelineToIdleLocked("dispatch stale completion")
                } else if (isRunning.get()) {
                    transitionPipelineStageLocked(SttPipelineStage.CAPTURING, "dispatch complete")
                } else {
                    transitionPipelineToIdleLocked("dispatch complete not running")
                }
            }
        }
    }

    private fun createOnComplete(sessionEpochAtSubmission: Long, completeStopPath: Boolean): () -> Unit {
        return {
            synchronized(stateLock) {
                isInferencing.set(false)
                if (completeStopPath && sessionEpochAtSubmission == currentSessionEpoch) {
                    transitionPipelineToIdleLocked("stop inference complete")
                    currentSessionEpoch = 0L
                    lifecycleController.onStop()
                    lifecycleController.onReset()
                }
            }
        }
    }

    private fun handleInferenceRejected() {
        synchronized(stateLock) {
            isInferencing.set(false)
            if (isRunning.get()) {
                transitionPipelineStageLocked(SttPipelineStage.CAPTURING, "inference submit rejected")
            } else {
                transitionPipelineToIdleLocked("inference submit rejected")
            }
        }
    }

    private fun handleInferenceError(t: Throwable) {
        synchronized(stateLock) {
            isInferencing.set(false)
            if (isRunning.get()) {
                transitionPipelineStageLocked(SttPipelineStage.CAPTURING, "inference submit throwable")
            } else {
                transitionPipelineToIdleLocked("inference submit throwable")
            }
        }
        callbackDispatcher.dispatchError(t)
    }

    private fun enterInferencingLocked(reason: String): Boolean {
        if (!isInferencing.compareAndSet(false, true)) {
            return false
        }
        if (!transitionPipelineStageLocked(SttPipelineStage.INFERENCING, reason)) {
            isInferencing.set(false)
            return false
        }
        return true
    }

    private fun transitionPipelineStageLocked(newStage: SttPipelineStage, reason: String): Boolean {
        return pipelineState.transitionTo(newStage, reason)
    }

    private fun transitionPipelineToIdleLocked(reason: String): Boolean {
        return transitionPipelineStageLocked(SttPipelineStage.IDLE, reason)
    }

    internal fun currentPipelineStageForTest(): SttPipelineStage {
        return synchronized(stateLock) {
            pipelineState.currentStage
        }
    }

    /**
     * Returns true if the given JSON result string represents an error.
     */
    private fun hasJsonError(json: String): Boolean {
        return json.contains("\"type\":\"error\"")
    }

    companion object {
        @Volatile
        var instance: SpeechToText? = null
            internal set

        @Volatile
        private var pendingListener: ((String) -> Unit)? = null

        /**
         * Load the STT model and configure the pipeline without starting capture.
         *
         * Creates the singleton on first call; subsequent calls delegate to the
         * existing instance's [loadModel].
         *
         * Safe to call at app startup. The model is loaded and warmup runs on the
         * Whisper executor thread. Call [startSession] when you want to begin
         * audio capture.
         *
         * @param context Application or Activity context (applicationContext is used internally).
         * @param configJson A JSON string conforming to the input config schema.
         * @return A JSON result string on success, or a JSON error string on failure.
         */
        fun loadModel(context: Context, configJson: String): String {
            val appCtx = context.applicationContext

            val stt = instance ?: synchronized(this) {
                instance ?: SpeechToText(
                    appCtx,
                    WhisperBridge,
                    CaptureManager()
                ).also { newInstance ->
                    instance = newInstance
                    pendingListener?.let { newInstance.setOnMessageListener(it) }
                }
            }

            return stt.loadModel(configJson)
        }

        /**
         * Start a capture session.
         *
         * Delegates to the singleton instance's [startSession].
         * [loadModel] must have been called first.
         *
         * @return A JSON result string on success, or a JSON error string on failure.
         */
        fun startSession(): String {
            val stt = instance
            if (stt == null) {
                return SttJsonAdapter.buildErrorJson(
                    "SESSION_FAILED",
                    "loadModel() must be called before startSession()"
                )
            }
            return stt.startSession()
        }

        /**
         * Initialise STT and start a session in a single call.
         *
         * The singleton is created on the first call; subsequent calls
         * delegate to the existing instance's [init].
         *
         * This is a convenience method. For responsive UI startup, prefer
         * calling [loadModel] at app startup and [startSession] on the
         * Start button.
         *
         * @param context Application or Activity context (applicationContext is used internally).
         * @param configJson A JSON string conforming to the input config schema.
         * @return A JSON result string on success, or a JSON error string on failure.
         */
        fun init(context: Context, configJson: String): String {
            val appCtx = context.applicationContext

            val stt = instance ?: synchronized(this) {
                instance ?: SpeechToText(
                    appCtx,
                    WhisperBridge,
                    CaptureManager()
                ).also { newInstance ->
                    instance = newInstance
                    pendingListener?.let { newInstance.setOnMessageListener(it) }
                }
            }

            return stt.init(configJson)
        }

        /**
         * Transcribe the current utterance via the singleton instance.
         *
         * If [loadModel] has not been called, a warning is logged and the call is ignored.
         */
        fun transcribe() {
            val stt = instance
            if (stt == null) {
                SttLogger.lifecycleW("SpeechToText.transcribe() called before init() — ignoring")
                return
            }
            stt.transcribe()
        }

        /**
         * Register a JSON message listener.
         *
         * Safe to call before [loadModel]; the listener is buffered and wired
         * to the singleton once it is created.
         */
        fun setOnMessageListener(listener: (String) -> Unit) {
            pendingListener = listener
            instance?.setOnMessageListener(listener)
        }

        /**
         * Reset the singleton for testing.
         *
         * Clears the instance and any pending listener so the next call
         * to [init] creates a fresh singleton.
         */
        internal fun resetForTest() {
            instance = null
            pendingListener = null
        }
    }
}
