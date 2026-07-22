package dev.barrycade.voicecore.stt

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Main entry point for the STT pipeline.
 *
 * ## Public API (all return JSON strings)
 *
 * - `init(configJson)` — load model + start session (first call), or start session only (subsequent calls)
 * - `loadModelOnly(configJson)` — load model and configure pipeline without starting capture
 * - `configure(configJson)` — lightweight runtime config change
 * - `transcribe()` — synchronous: block until utterance complete, return JSON
 *
 * ## Intended lifecycle (Manual mode)
 *
 * 1. App startup → `loadModelOnly()` → load model, warm-up, no capture, no session
 * 2. User presses Start → `init()` → start session, begin capture, begin polling
 * 3. User speaks → PCM accumulates in session buffer
 * 4. User presses Stop → `transcribe()` → finalise PCM, run inference, return result
 * 5. Pipeline returns to IDLE, ready for next session
 *
 * ## Internal lifecycle
 *
 * Internal methods (`loadModel`, `startSession`) are invoked automatically
 * by the public API. They must not be called directly.
 *
 * ## Threading model
 *
 * | Thread                | Responsibility                                         |
 * |-----------------------|--------------------------------------------------------|
 * | Caller thread         | Public API (`init`, `configure`, `transcribe`)          |
 * | Audio capture (T1)    | AudioRecord read → PCM frame enqueue                   |
 * | Capture drain (T2)    | Warm‑up buffering into session buffer                  |
 * | Processor (T3)        | VAD, utterance accumulation, PCM polling               |
 * | Whisper executor (T4) | Model load/unload/inference                            |
 *
 * ## Lock boundaries
 *
 * - `stateLock` serialises **all** public API methods.
 * - `stateLock` is **not** held across blocking operations (thread joins,
 *   native inference, executor shutdown).
 * - Blocking operations occur **outside** the lock to avoid deadlock.
 *
 * ## Re‑entrancy rules
 *
 * Callers must **not** invoke lifecycle methods from within callbacks.
 * Doing so risks deadlock or undefined behaviour.
 */

class SpeechToText internal constructor(
    internal val whisperModel: WhisperModel,
    internal val captureManager: SessionManager
) {
    // ── Public no-arg constructor ────────────────────────────────────────

    /**
     * Public no-arg constructor.
     *
     * Creates the STT engine with default dependencies (WhisperBridge, CaptureManager).
     * Call [init] to load the model and start the pipeline.
     */
    constructor() : this(WhisperBridge, CaptureManager())

    // ── Controller references ────────────────────────────────────────────

    /** Capture lifecycle controller, wraps the [SessionManager]. */
    private var captureController: SttCaptureController = SttCaptureController(captureManager)

    private val sessionController = SttSessionController()
    private val modeController = SttModeController()
    private val threadController = SttThreadController()
    private val callbackDispatcher = SttCallbackDispatcher()

    internal val lifecycleController = SttLifecycleController(
        sttErrorListener = callbackDispatcher.getSttErrorListener()
            ?: SttErrorListener { _ -> }
    )

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

    /** Model manager — reconstructed inside loadModel() once listener is registered. */
    private var modelManager: ModelManager

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
        // Temporary ModelManager — reconstructed inside loadModel() with the real listener.
        val placeholderListener = SttErrorListener { error ->
            SttLogger.error("code=${error.code.name}, message=\"${error.message}\" [placeholder listener]")
        }
        modelManager = ModelManager(
            modelPath = "",
            sttErrorListener = placeholderListener,
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
     * Internal — called by [init] and [reconfigure]. External callers must use
     * [init] or the companion [loadModel].
     *
     * Parses the JSON config, loads the model, runs warm-up (if configured),
     * and builds internal scaffolding. Does NOT start audio capture or begin
     * a session — [init] handles the full lifecycle.
     *
     * Safe to call repeatedly. Idempotent — subsequent calls return
     * immediately once the model is loaded.
     *
     * @param configJson A JSON string conforming to the input config schema.
     *                   See [SttJsonAdapter.parseConfig] for the expected shape.
     * @return An [SttError] on failure, or null on success.
     */
    internal fun loadModel(configJson: String): SttError? {
        val sttConfig = try {
            SttJsonAdapter.parseConfig(configJson)
        } catch (e: IllegalArgumentException) {
            val error = SttError(
                code = SttErrorCode.CONFIG_PARSE_FAILED,
                message = e.message ?: "Config parse failed",
                cause = e,
                details = listOf("configJson=$configJson")
            )
            callbackDispatcher.dispatchError(error)
            return error
        }

        // ── Report which optional fields defaulted ──────────────────────
        val configFeedback = buildConfigAppliedFeedback(configJson)
        if (configFeedback != null) {
            callbackDispatcher.dispatchMessage(configFeedback)
        }

        synchronized(stateLock) {
            // ── Idempotency guard ─────────────────────────────────────────────
            if (sessionConfig != null) {
                SttLogger.lifecycle("loadModel: already initialised — returning SUCCESS")
                return null
            }

            // ── Step 1: Build immutable session config ────────────────────────
            val sessionCfg = SttSessionConfig.from(sttConfig)
            sessionConfig = sessionCfg
            val runtimeCfg = sessionCfg.runtimeConfig

            // ── Step 2: Reconstruct ModelManager with the real listener and load model ──
            val listener = callbackDispatcher.getSttErrorListener()
            if (listener != null) {
                modelManager = ModelManager(
                    modelPath = sessionCfg.modelPath,
                    sttErrorListener = listener,
                    whisperModel = whisperModel
                )
            } else {
                modelManager.updateModelPath(sessionCfg.modelPath)
            }
            if (!modelManager.loadModelIfNeeded()) {
                sessionConfig = null
                val error = SttError(
                    code = SttErrorCode.MODEL_LOAD_FAILED,
                    message = "Model load failed"
                )
                callbackDispatcher.dispatchError(error)
                return error
            }

            // ── Step 3: Warm-up (once per app lifetime) ───────────────────────
            if (sessionCfg.warmupEnabled) {
                modelManager.runWarmup(sessionCfg.warmupDurationMs)
            }

            // ── Step 4: Reconstruct CaptureManager with runtime buffer size ───
            val sessionManager: SessionManager
            if (captureController.sessionManager is CaptureManager) {
                val oldManager = captureController.sessionManager as CaptureManager
                oldManager.shutdown()
                val newManager = CaptureManager(
                    bufferSizeSamples = sessionCfg.bufferSizeSamples,
                    sttErrorListener = callbackDispatcher.getSttErrorListener(),
                    debugLoggingEnabled = runtimeCfg.debugLoggingEnabled
                )
                captureController = SttCaptureController(newManager)
                sessionManager = newManager
            } else {
                sessionManager = captureController.sessionManager
            }

            // ── Step 5: Construct STT scaffolding via mode controller ─────────
            // The onTimeoutRef is a reference to transcribe() so that session
            // timeout fires on the MinimalPollingController worker thread.
            val sessionTimeoutMs = runtimeCfg.sessionTimeoutMs
            val onTimeoutRef: () -> Unit = if (sessionTimeoutMs > 0) {
                { this@SpeechToText.transcribe() }
            } else {
                {}
            }
            modeController.selectController(
                config = runtimeCfg,
                captureManager = sessionManager,
                stopRequestedRef = stopRequest.asSupplier(),
                onTimeoutRef = onTimeoutRef
            )

            // ── Step 5a: Tear down any previous processing controller ─────────
            // When switching modes via reconfigure(), the old processing
            // controller from the previous config must be stopped and
            // discarded before a new one is created.
            val oldProcessingController = processingController
            if (oldProcessingController != null) {
                oldProcessingController.stop()
                processingController = null
                vad = null
            }

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

            return null
        }
    }

    /**
     * Load the STT model and configure the pipeline without starting audio capture.
     *
     * This is intended for preload scenarios where the model must be loaded
     * and warmed up before the user presses Start (e.g. app startup). After
     * calling this method, call [init] to begin a capture session.
     *
     * Safe to call multiple times — idempotent after first success.
     *
     * @param configJson A JSON string conforming to the input config schema.
     *                   See [SttJsonAdapter.parseConfig] for the expected shape.
     * @return JSON response. Success: {"type":"init","status":"ok"}
     *         Error: {"type":"error","code":"...","message":"..."}
     */
    fun loadModelOnly(configJson: String): String {
        val error = loadModel(configJson)
        if (error != null) {
            return SttJsonAdapter.buildErrorJson(error.code, error.message, error.details)
        }
        return """{"type":"init","status":"ok"}"""
    }

    /**
     * Apply new configuration at runtime without restarting the pipeline.
     *
     * Updates runtime-safe fields: sttMode, grammar, energyThreshold,
     * silenceTimeoutMs, partialsEnabled, autoReturn, and strategy config.
     *
     * Safe to call between utterances. Not allowed during active capture
     * or inference.
     *
     * @param configJson A JSON string with the fields to update.
     * @return JSON response. Success: {"type":"config","status":"ok"}
     *         Error: {"type":"error","code":"...","message":"..."}
     */
    fun configure(configJson: String): String {
        synchronized(stateLock) {
            if (sessionConfig == null) {
                val error = SttError(
                    code = SttErrorCode.CONFIG_NOT_SET,
                    message = "init() must be called before configure()"
                )
                return SttJsonAdapter.buildErrorJson(error.code, error.message, error.details)
            }
            if (isRunning.get() || isInferencing.get()) {
                val error = SttError(
                    code = SttErrorCode.PIPELINE_ILLEGAL_STATE,
                    message = "Cannot configure during active session"
                )
                return SttJsonAdapter.buildErrorJson(error.code, error.message, error.details)
            }
            sessionConfig = null
        }
        val error = loadModel(configJson)
        if (error != null) {
            return SttJsonAdapter.buildErrorJson(error.code, error.message, error.details)
        }
        return """{"type":"config","status":"ok"}"""
    }

    /**
     * Start a capture session.
     *
     * Internal — called by [init]. External callers must use the companion
     * [startSession].
     *
     * Begins audio capture, starts the processor, and transitions to
     * the CAPTURING pipeline stage. Must be called after [loadModel]
     * has completed successfully.
     *
     * Idempotent — returns immediately if a session is already active.
     *
     * @return An [SttError] on failure, or null on success.
     */
    internal fun startSession(): SttError? {
        synchronized(stateLock) {
            // ── Guard: must have loaded a model ───────────────────────────────
            val cfg = sessionConfig
            if (cfg == null) {
                val error = SttError(
                    code = SttErrorCode.CONFIG_NOT_SET,
                    message = "loadModel() must be called before startSession()"
                )
                callbackDispatcher.dispatchError(error)
                return error
            }

            val runtimeCfg = cfg.runtimeConfig

            if (!modelManager.isReady) {
                val error = SttError(
                    code = SttErrorCode.MODEL_LOAD_FAILED,
                    message = "Model not ready"
                )
                callbackDispatcher.dispatchError(error)
                return error
            }
            if (isInferencing.get()) {
                val error = SttError(
                    code = SttErrorCode.INFERENCE_FAILED,
                    message = "Inference already active"
                )
                callbackDispatcher.dispatchError(error)
                return error
            }
            if (lifecycleController.isRecording() || pipelineState.currentStage == SttPipelineStage.CAPTURING) {
                return null
            }
            if (!lifecycleController.canStartSession()) {
                val error = SttError(
                    code = SttErrorCode.PIPELINE_ILLEGAL_STATE,
                    message = "Cannot start session from state ${lifecycleController.currentState}"
                )
                callbackDispatcher.dispatchError(error)
                return error
            }

            events.manualStartPressed.raise()
            if (!runtimeCfg.startStrategy.shouldStart(events, vad)) {
                return null
            }

            currentSessionEpoch = sessionEpoch.incrementAndGet()
            stopRequest.clear()
            if (!transitionPipelineStageLocked(SttPipelineStage.CAPTURING, "startSession")) {
                val error = SttError(
                    code = SttErrorCode.PIPELINE_ILLEGAL_STATE,
                    message = "Pipeline stage transition failed"
                )
                callbackDispatcher.dispatchError(error)
                return error
            }
            sessionController.beginSession()
            val captureStarted = captureController.startCapture(modeController.isManualMode())
            if (!captureStarted) {
                val error = SttError(
                    code = SttErrorCode.CAPTURE_FAILED,
                    message = "Audio capture failed to start"
                )
                callbackDispatcher.dispatchError(error)
                transitionPipelineToIdleLocked("capture start failed")
                currentSessionEpoch = 0L
                lifecycleController.onStop()
                lifecycleController.onReset()
                return error
            }

            if (modeController.isManualMode()) {
                captureController.activatePcmCapture()
                sessionController.beginUtteranceTiming()
                isRunning.set(true)
                modeController.minimalProcessorController?.start()
            } else {
                startProcessor()
            }

            SttLogger.lifecycle("startSession: capture started")
            return null
        }
    }

    /**
     * Initialise the STT subsystem and start a capture session.
     *
     * ## First call (sessionConfig == null)
     *
     * Loads the model, configures the pipeline, and starts a capture session.
     * Equivalent to calling [loadModelOnly] followed by [startSession].
     *
     * ## Subsequent calls (sessionConfig != null)
     *
     * Starts a new capture session only. The model and pipeline remain
     * loaded from the first call. Call this from the Start button to
     * begin a fresh session without re-loading the model.
     *
     * Safe to call multiple times. After the first call, subsequent calls
     * start a new session without re-loading the model.
     *
     * @param configJson A JSON string conforming to the input config schema.
     *                   See [SttJsonAdapter.parseConfig] for the expected shape.
     * @return JSON response. Success: {"type":"init","status":"ok"}
     *         Error: {"type":"error","code":"...","message":"..."}
     */
    fun init(configJson: String): String {
        if (sessionConfig != null) {
            // Model already loaded — start a new capture session.
            val sessionError = startSession()
            if (sessionError != null) {
                return SttJsonAdapter.buildErrorJson(sessionError.code, sessionError.message, sessionError.details)
            }
            return """{"type":"init","status":"ok"}"""
        }
        val error = loadModel(configJson)
        if (error != null) {
            return SttJsonAdapter.buildErrorJson(error.code, error.message, error.details)
        }
        val sessionError = startSession()
        if (sessionError != null) {
            return SttJsonAdapter.buildErrorJson(sessionError.code, sessionError.message, sessionError.details)
        }
        return """{"type":"init","status":"ok"}"""
    }

    /**
     * Block until the current utterance is complete, then return the
     * transcription result as a JSON string.
     *
     * This method is synchronous — it blocks the caller thread until
     * inference finishes or the silence timeout expires. It never blocks
     * indefinitely.
     *
     * @return JSON result string. Success:
     *         {"type":"result","text":"...","code":"SUCCESS","timing":{...}}
     *         Silence/empty: {"type":"result","text":"","code":"SILENCE"}
     *         Error: {"type":"error","code":"...","message":"..."}
     */
    fun transcribe(): String {
        // ── Phase 1: Prepare inside stateLock (fast, non-blocking) ────────
        val prepared = synchronized(stateLock) {
            prepareTranscribeLocked()
        }
        if (prepared == null) {
            return """{"type":"result","text":"","code":"SILENCE"}"""
        }

        // ── Phase 2: Await result OUTSIDE stateLock (blocks on executor) ──
        return inferenceController.submitAndAwait(
            request = prepared.request,
            decideDispatch = prepared.decideDispatch,
            onPostDispatch = prepared.onPostDispatch,
            onComplete = prepared.onComplete,
            timeoutMs = 30_000L
        )
    }

    /**
     * Register a JSON message listener for the JSON-boundary API.
     *
     * Receives result, error, and debug JSON strings delivered from the
     * auto-silence path (when the UtteranceAccumulator detects silence
     * and triggers inference without an explicit [transcribe] call).
     *
     * In Manual/Manual mode the result is returned directly from
     * [transcribe]. In Manual/Auto mode the result is delivered through
     * this listener because inference is triggered internally by the
     * UtteranceAccumulator, not by the caller.
     *
     * Must be registered before the first session starts to receive
     * auto-silence results.
     */
    fun setOnMessageListener(listener: (String) -> Unit) {
        callbackDispatcher.setOnMessageListener(listener)
    }

    /**
     * Prepare transcribe state inside [stateLock].
     *
     * Extracts PCM, builds the inference request, and creates callbacks.
     * Returns null if there is nothing to transcribe.
     */
    private fun prepareTranscribeLocked(): PreparedTranscribe? {
        // Ignore manual stop if auto-silence or another path already ended capture.
        if (pipelineState.currentStage != SttPipelineStage.CAPTURING) {
            return null
        }

        val cfg = sessionConfig ?: return null

        if (!isRunning.get()) {
            SttLogger.lifecycle("transcribe() ignored -- session not running")
            return null
        }

        val runtimeCfg = cfg.runtimeConfig
        val elapsedMs = sessionController.endSession().toInt()

        // Session timeout check
        val sessionTimeoutMs = runtimeCfg.sessionTimeoutMs
        val timedOut = sessionTimeoutMs > 0 && elapsedMs >= sessionTimeoutMs
        if (timedOut) {
            SttLogger.lifecycle("session timeout: ${elapsedMs}ms >= ${sessionTimeoutMs}ms — forcing finalisation")
        }

        events.manualStopPressed.raise()

        if (!timedOut && !runtimeCfg.stopStrategy.shouldStop(events, vad, elapsedMs)) {
            return null
        }

        if (lifecycleController.currentState is SttLifecycleState.STOPPED) {
            SttLogger.pcm("ignoring -- state=STOPPED")
            return null
        }

        if (lifecycleController.currentState is SttLifecycleState.FINALISING) {
            SttLogger.pcm("already FINALISING -- returning")
            return null
        }

        if (!transitionPipelineStageLocked(SttPipelineStage.FINALISING, "transcribe")) {
            SttLogger.lifecycleW("transcribe() ignored -- illegal stage from ${pipelineState.currentStage}")
            return null
        }

        isRunning.set(false)
        stopRequest.raise()
        modeController.stopController()

        val vadGate = modeController.minimalProcessorController?.vadGate
        val finalPcm = captureController.finaliseAndStop(vadGate)

        if (finalPcm.isEmpty()) {
            SttLogger.pcm("no PCM accumulated -- transitioning to STOPPED then READY")
            transitionPipelineToIdleLocked("transcribe empty pcm")
            currentSessionEpoch = 0L
            lifecycleController.onStop()
            lifecycleController.onReset()
            return null
        }

        lifecycleController.onFinalising()
        val timingMs = sessionController.currentPcmElapsedMs()

        val epoch = currentSessionEpoch
        if (epoch == 0L) {
            SttLogger.lifecycleW("transcribe() with no active session epoch -- dropping inference")
            transitionPipelineToIdleLocked("transcribe missing epoch")
            lifecycleController.onStop()
            lifecycleController.onReset()
            return null
        }

        if (!enterInferencingLocked("transcribe")) {
            SttLogger.lifecycleW("transcribe() ignored -- inference already active")
            return null
        }

        val vadMs = processingController?.vadActiveMs ?: 0L
        val utterMs = (processingController?.lastUtteranceDurationMs ?: 0).toLong()
        val returnCode = if (timedOut) SttReturnCode.SESSION_TIMEOUT else SttReturnCode.SUCCESS

        val request = SttInferenceController.InferenceRequest(
            pcm = finalPcm,
            code = returnCode,
            vadActiveMs = vadMs,
            utteranceMs = utterMs,
            captureMs = timingMs,
            preRollMs = runtimeCfg.preRollMs.toLong(),
            autoSilenceMs = runtimeCfg.autoSilenceMs.toLong(),
            pipelineStartMs = sessionController.utteranceStartMs(),
            sessionEpochAtSubmission = epoch
        )

        val decideDispatch = createDecideDispatch(epoch)
        val onPostDispatch = createOnPostDispatch(epoch, completeStopPath = true)
        val onComplete = createOnComplete(epoch, completeStopPath = true)

        return PreparedTranscribe(
            request = request,
            decideDispatch = decideDispatch,
            onPostDispatch = onPostDispatch,
            onComplete = onComplete
        )
    }

    /**
     * Holder for transcribe preparation state, extracted from [stateLock]
     * so the blocking await can happen outside the lock.
     */
    private data class PreparedTranscribe(
        val request: SttInferenceController.InferenceRequest,
        val decideDispatch: () -> SttInferenceController.DispatchDecision,
        val onPostDispatch: () -> Unit,
        val onComplete: () -> Unit
    )

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
     *
     * On init failure, the error is dispatched AND the pipeline is reset to
     * a safe state so subsequent calls do not silently succeed.
     */
    private fun startProcessor() {
        if (isRunning.get()) return

        if (!modelManager.isReady) {
            SttLogger.lifecycleW("startProcessor() called before model ready -- ignoring")
            return
        }
        if (modelManager.initFailed) {
            callbackDispatcher.dispatchError(
                SttError(
                    code = SttErrorCode.MODEL_LOAD_FAILED,
                    message = "Model initialisation failed"
                )
            )
            transitionPipelineToIdleLocked("startProcessor initFailed")
            currentSessionEpoch = 0L
            lifecycleController.onStop()
            lifecycleController.onReset()
            return
        }
        if (forceAudioInitFailure) {
            callbackDispatcher.dispatchError(
                SttError(
                    code = SttErrorCode.CAPTURE_FAILED,
                    message = "Forced test: AudioCapture init"
                )
            )
            transitionPipelineToIdleLocked("startProcessor forceAudioInitFailure")
            currentSessionEpoch = 0L
            lifecycleController.onStop()
            lifecycleController.onReset()
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
        val stopStrategy = sessionConfig?.runtimeConfig?.stopStrategy
        val isAutoSilence = stopStrategy is AutoSilenceStop
        return {
            synchronized(stateLock) {
                if (sessionEpochAtSubmission != currentSessionEpoch) {
                    transitionPipelineToIdleLocked("dispatch stale completion")
                } else if (isAutoSilence) {
                    // AutoSilenceStop: do not transition back to CAPTURING.
                    // The utterance boundary is a session-level stop — let
                    // onComplete handle the full teardown.
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
                } else if (!completeStopPath && sessionEpochAtSubmission == currentSessionEpoch) {
                    // ── AutoSilenceStop: end the session after inference ──────
                    // ManualStart + AutoSilenceStop is a single-utterance mode.
                    // When the UtteranceAccumulator delivers UtteranceReady via
                    // handleUtteranceReady(), inference completes here. If the
                    // active stop strategy is AutoSilenceStop, this was a
                    // session-level stop — end the session cleanly.
                    val stopStrategy = sessionConfig?.runtimeConfig?.stopStrategy
                    if (stopStrategy is AutoSilenceStop) {
                        processingController?.stop()

                        // Hard-reset accumulator state to prevent stale PCM
                        // from the post-utterance buffering interval carrying
                        // over to the next session.
                        processingController?.let { procCtrl ->
                            procCtrl.resetAccumulator()
                        }

                        captureController.finaliseAndStop(null)
                        isRunning.set(false)
                        transitionPipelineToIdleLocked("auto-silence session complete")
                        currentSessionEpoch = 0L
                        lifecycleController.onFinalising()
                        lifecycleController.onStop()
                        lifecycleController.onReset()
                    }
                    // Other non-completeStopPath cases (future multi-utterance
                    // modes) keep the existing behaviour — onPostDispatch
                    // transitions back to CAPTURING.
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
        callbackDispatcher.dispatchError(
            SttError(
                code = SttErrorCode.INTERNAL_EXCEPTION,
                message = t.message ?: "Unknown inference error",
                cause = t
            )
        )
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
     * Build a "config applied" feedback message reporting which optional fields
     * were absent from the input JSON and received defaults.
     *
     * Output shape:
     * ```json
     * {
     *   "type": "config",
     *   "code": "DEFAULTS_USED",
     *   "fields": ["highPassCutoffHz"],
     *   "defaults": {
     *     "highPassCutoffHz": 0
     *   }
     * }
     * ```
     *
     * Returns null when no fields defaulted (all optional fields were explicit).
     */
    private fun buildConfigAppliedFeedback(json: String): String? {
        val knownOptionalKeys = listOf(
            "highPassCutoffHz" to "0",
            "zcrEnabled" to "false",
            "warmupEnabled" to "false",
            "warmupDurationMs" to "0",
            "sessionTimeoutMs" to "0",
            "bufferSizeSamples" to "4000"
        )

        val defaultedFields = mutableListOf<String>()
        val defaultValues = mutableMapOf<String, String>()

        for ((key, defaultVal) in knownOptionalKeys) {
            val keyRegex = Regex("\"$key\"\\s*:")
            if (!keyRegex.containsMatchIn(json)) {
                defaultedFields.add(key)
                defaultValues[key] = defaultVal
            }
        }

        if (defaultedFields.isEmpty()) return null

        val sb = StringBuilder()
        sb.append("{\"type\":\"config\",\"code\":\"DEFAULTS_USED\",\"fields\":[")
        defaultedFields.forEachIndexed { index, field ->
            if (index > 0) sb.append(',')
            sb.append('"').append(field).append('"')
        }
        sb.append("],\"defaults\":{")
        var first = true
        for (field in defaultedFields) {
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(field).append("\":").append(defaultValues[field])
        }
        sb.append("}}")
        return sb.toString()
    }

}
