package dev.barrycade.voicecore.stt

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Main entry point for the STT pipeline.
 *
 * ## Singleton lifecycle
 *
 * Obtain the single instance via [SpeechToTextProvider.get]. The model is loaded
 * exactly once per app lifetime during [initStt]. Subsequent calls to [initStt]
 * return [SttReturnCode.SUCCESS] immediately without reloading the model or
 * reconstructing scaffolding.
 *
 * ## Session lifecycle
 *
 * 1. Call [setConfig] with a validated [SttConfig].
 * 2. Call [initStt] once to load the model, run warm-up, and build scaffolding.
 * 3. Call [startSession] to begin recording and transcription.
 * 4. Use [stopAndTranscribe] to stop manually.
 * 5. Call [resetForNextSession] to reuse this instance for a new utterance.
 * 6. Call [destroy] to release all resources (app shutdown).
 *
 * ## Threading and lock model
 *
 * ### Thread ownership
 *
 * | Thread | Owns | Notes |
 * |--------|------|-------|
 * | Caller thread | Public lifecycle methods ([setConfig], [initStt], [startSession], etc.) | Serialized via [stateLock] |
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
 * - [sessionEpoch] is an [AtomicLong] incremented on each [startSession]
 *   and [resetForNextSession].
 * - [currentSessionEpoch] is snapshotted at inference submission.
 * - Callbacks whose epoch does not match [currentSessionEpoch] are dropped.
 *
 * ### Result and error callbacks
 *
 * Result and error callbacks are **not** delivered on the main thread.
 * Callers must post to their own [android.os.Handler] or
 * [kotlinx.coroutines.Dispatchers.Main] if main-thread delivery is required.
 *
 * | Callback | Delivery thread |
 * |---|---|
 * | [onResult] | Whisper executor thread |
 * | [onResultWithTiming] | Whisper executor thread |
 * | [onError] | The thread that encountered the error |
 * | [sttErrorListener] | Same as [onError] |
 *
 * All lifecycle methods ([setConfig], [initStt], [startSession],
 * [stopAndTranscribe], [destroy], [resetForNextSession]) are serialized
 * internally via [stateLock].
 *
 * Callers MUST NOT call lifecycle methods from within callbacks — doing so
 * will produce undefined behavior (potential deadlock or re-entrancy).
 */
class SpeechToText internal constructor(
    context: Context?,
    private val whisperModel: WhisperModel = WhisperBridge,
    captureManager: SessionManager = CaptureManager()
) {

    // ── Controller references ────────────────────────────────────────────

    /** Capture lifecycle controller, wraps the [SessionManager]. */
    internal var captureController = SttCaptureController(captureManager)

    internal val lifecycleController = SttLifecycleController()
    internal val sessionController = SttSessionController()
    internal val modeController = SttModeController()
    internal val threadController = SttThreadController()
    internal val callbackDispatcher = SttCallbackDispatcher()

    // ── Test options ─────────────────────────────────────────────────────

    /**
     * When true, AudioCapture initialisation will fail.
     * Set via [setDebugOptions].
     */
    private var forceAudioInitFailure: Boolean = false

    /**
     * When true, UtteranceAccumulator will force a timeout.
     * Set via [setDebugOptions], read at [initStt] time.
     */
    private var forceAccumulatorTimeout: Boolean = false

    /**
     * Timing listener, called after each inference completes.
     *
     * **Delivery thread:** Whisper executor thread.
     *
     * @param pcmMs       Wall-clock duration of PCM capture.
     * @param vadActiveMs Total time speech was detected by VAD.
     * @param whisperMs   Duration of the Whisper inference call.
     * @param totalMs     End-to-end pipeline time from utterance start to result.
     */
    var onTimingListener: ((pcmMs: Long, vadActiveMs: Long, whisperMs: Long, totalMs: Long) -> Unit)? = null
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
    internal val modelManager: ModelManager

    /** Dedicated inference adapter controller. */
    internal val inferenceController: SttInferenceController

    /** Processing controller for Auto mode. null in Manual mode. */
    internal var processingController: SttProcessingController? = null

    /**
     * VAD instance for strategy evaluation.
     * null until initialised (Manual mode never initialises it).
     * Set during [initStt] alongside [processingController].
     */
    private var vad: Vad? = null

    /**
     * Immutable session config, built during [initStt].
     * null until [initStt] completes successfully.
     */
    private var sessionConfig: SttSessionConfig? = null

    /** Observable events for start/stop strategies. */
    private val events: SttEvents = SttEvents()

    /**
     * Single-owner stop signal. Written only in [stopAndTranscribe],
     * cleared in [resetForNextSession] and [destroy].
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
        SttLogger.lifecycle("SpeechToText constructed — model NOT loaded. Call initStt() to initialise.")
    }

    // ------- Public API ------------------------------------------------

    /**
     * Register a listener for transcription results.
     */
    fun setOnResultListener(l: (String) -> Unit) {
        callbackDispatcher.setOnResultListener(l)
    }

    /**
     * Register a listener for transcription results with timing snapshot.
     */
    fun setOnResultWithTimingListener(l: (text: String, code: SttReturnCode, timing: SttTimingSnapshot?) -> Unit) {
        callbackDispatcher.setOnResultWithTimingListener(l)
    }

    /**
     * Register a generic error listener.
     */
    fun setOnErrorListener(l: (Throwable) -> Unit) {
        callbackDispatcher.setOnErrorListener(l)
    }

    /**
     * Register a structured STT error listener.
     */
    fun setSttErrorListener(l: SttErrorListener) {
        callbackDispatcher.setSttErrorListener(l)
    }

    /**
     * Set debug/test options for the pipeline.
     */
    fun setDebugOptions(
        forceAudioInitFailure: Boolean = false,
        forceWhisperLoadFailure: Boolean = false,
        forceTimeout: Boolean = false
    ) {
        this.forceAudioInitFailure = forceAudioInitFailure
        this.forceAccumulatorTimeout = forceTimeout
        modelManager.forceWhisperLoadFailure = forceWhisperLoadFailure
    }

    /**
     * Set the [SttConfig] for a subsequent [startSession] call.
     *
     * This is the preferred entry point. Replaces the legacy [setConfig] that
     * accepts [SttRunConfig].
     */
    fun setConfig(config: SttConfig): SessionResult {
        synchronized(stateLock) {
            return SessionResult(SttReturnCode.SUCCESS, null)
        }
    }

    /**
     * Initialise the STT system for the given [config] without activating
     * any STT processing behaviours.
     *
     * This is the preferred entry point. Replaces the legacy [initStt] that
     * accepts [SttRunConfig].
     */
    fun initStt(config: SttConfig): SessionResult {
        synchronized(stateLock) {
            // ── Idempotency guard ─────────────────────────────────────────────
            if (sessionConfig != null || lifecycleController.currentState is SttLifecycleState.READY) {
                SttLogger.lifecycle("initStt: already initialised — returning SUCCESS immediately")
                return SessionResult(SttReturnCode.SUCCESS, null)
            }

            // ── Step 1: Build immutable session config ────────────────────────
            val sessionCfg = SttSessionConfig.from(config)
            sessionConfig = sessionCfg

            val runtimeCfg = sessionCfg.runtimeConfig

            // ── Step 2: Update model path and load model ──────────────────────
            modelManager.updateModelPath(sessionCfg.modelPath)

            if (!modelManager.loadModelIfNeeded()) {
                sessionConfig = null
                return SessionResult(SttReturnCode.ENGINE_ERROR, null)
            }

            // ── Step 3: Mandatory warm-up (once per app lifetime) ─────────────
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

            // ── Step 6: Mark initialised ──────────────────────────────────────
            lifecycleController.onReady()
            SttLogger.lifecycle("initStt: initialisation complete")

            return SessionResult(SttReturnCode.SUCCESS, null)
        }
    }

    /**
     * Start an STT session using the config previously set via [setConfig].
     */
    fun startSession(): SessionResult {
        synchronized(stateLock) {
            val cfg = sessionConfig
            if (cfg == null) {
                return SessionResult(SttReturnCode.CONFIG_NOT_SET, null)
            }

            val runtimeCfg = cfg.runtimeConfig

            if (!modelManager.isReady) {
                SttLogger.lifecycleW("startSession() called but model is not ready — returning ENGINE_ERROR")
                return SessionResult(SttReturnCode.ENGINE_ERROR, null)
            }

            if (isInferencing.get()) {
                SttLogger.lifecycleW("startSession() called while inference is still active — returning ENGINE_ERROR")
                return SessionResult(SttReturnCode.ENGINE_ERROR, null)
            }

            if (!lifecycleController.canStartSession()) {
                SttLogger.lifecycleW("startSession() called from ${lifecycleController.currentState} -- ignoring")
                return SessionResult(SttReturnCode.ENGINE_ERROR, null)
            }

            events.manualStartPressed.raise()

            if (!runtimeCfg.startStrategy.shouldStart(events, vad)) {
                return SessionResult(SttReturnCode.SUCCESS, null)
            }

            currentSessionEpoch = sessionEpoch.incrementAndGet()
            if (!transitionPipelineStageLocked(SttPipelineStage.CAPTURING, "startSession")) {
                return SessionResult(SttReturnCode.ENGINE_ERROR, null)
            }
            sessionController.beginSession()
            captureController.startCapture(modeController.isManualMode())

            if (modeController.isManualMode()) {
                captureController.activatePcmCapture()
                modeController.minimalProcessorController?.start()
            } else {
                startProcessor()
            }

            return SessionResult(SttReturnCode.SUCCESS, null)
        }
    }

    /**
     * Stop the current session and transcribe accumulated audio.
     */
    fun stopAndTranscribe() {
        synchronized(stateLock) {
            val cfg = sessionConfig
            if (cfg == null) return

            val runtimeCfg = cfg.runtimeConfig
            val elapsedMs = sessionController.endSession().toInt()

            SttLogger.pcm("[STOP] entered -- isRunning=${isRunning.get()}, state=${lifecycleController.currentState}")

            events.manualStopPressed.raise()

            if (!runtimeCfg.stopStrategy.shouldStop(events, vad, elapsedMs)) {
                return
            }

            if (lifecycleController.currentState is SttLifecycleState.STOPPED) {
                SttLogger.pcm("[STOP] ignoring -- state=STOPPED")
                return
            }

            if (lifecycleController.currentState is SttLifecycleState.FINALISING) {
                SttLogger.pcm("[STOP] already FINALISING -- returning")
                return
            }

            if (!transitionPipelineStageLocked(SttPipelineStage.FINALISING, "stopAndTranscribe")) {
                SttLogger.lifecycleW("stopAndTranscribe() ignored -- illegal stage from ${pipelineState.currentStage}")
                return
            }

            // ── Finalise PCM via CaptureController (raw PCM, no VAD) ────
            isRunning.set(false)
            stopRequest.raise()

            modeController.stopController()

            val finalPcm = captureController.finaliseAndStop()

            if (finalPcm.isEmpty()) {
                SttLogger.pcm("[STOP] no PCM accumulated -- transitioning to STOPPED then READY")
                transitionPipelineToIdleLocked("stopAndTranscribe empty pcm")
                currentSessionEpoch = 0L
                lifecycleController.onStop()
                lifecycleController.onReset()
                return
            }

            // Transition to FINALISING (inference pending).
            lifecycleController.onFinalising()

            val timingMs = sessionController.currentPcmElapsedMs()

            val epoch = currentSessionEpoch
            if (epoch == 0L) {
                SttLogger.lifecycleW("stopAndTranscribe() with no active session epoch -- dropping inference")
                transitionPipelineToIdleLocked("stopAndTranscribe missing epoch")
                lifecycleController.onStop()
                lifecycleController.onReset()
                return
            }

            if (!enterInferencingLocked("stopAndTranscribe")) {
                SttLogger.lifecycleW("stopAndTranscribe() ignored -- inference already active")
                return
            }

            val submitted = submitInferenceAndDispatch(
                pcm = finalPcm,
                code = SttReturnCode.SUCCESS,
                vadActiveMs = 0L,
                utteranceMs = 0L,
                captureMs = timingMs,
                sessionEpochAtSubmission = epoch,
                completeStopPath = true
            )

            if (!submitted) {
                SttLogger.lifecycleW("stopAndTranscribe() -- inference submission failed")
            }
        }
    }

    fun stop() = stopAndTranscribe()

    /**
     * Reset this instance for a new session without unloading the model.
     */
    fun resetForNextSession() {
        synchronized(stateLock) {
            SttLogger.lifecycle("resetForNextSession: state=${lifecycleController.currentState}")

            modeController.stopController()
            processingController?.stop()

            sessionController.resetSession()
            captureController.resetForNextSession()
            isRunning.set(false)
            stopRequest.clear()
            sessionController.resetUtteranceTiming()
            currentSessionEpoch = 0L
            sessionEpoch.incrementAndGet()
            transitionPipelineToIdleLocked("resetForNextSession")
            lifecycleController.onReset()
        }
    }

    // ------- destroy() ------------------------------------------------

    fun destroy() {
        synchronized(stateLock) {
            modeController.stopController()
            processingController?.stop()
            captureController.shutdown()
            isRunning.set(false)
            stopRequest.clear()
            isInferencing.set(false)
            currentSessionEpoch = 0L
            sessionEpoch.incrementAndGet()
            sessionController.resetUtteranceTiming()
            transitionPipelineToIdleLocked("destroy")
            sessionConfig = null
            lifecycleController.onDestroy()
        }
        // ── Unload model OUTSIDE stateLock ─────────────────────────────
        // unload() calls whisperModel.unloadModel() which is a blocking
        // JNI call. Holding stateLock across it would block all public
        // lifecycle methods.
        modelManager.unload()
        modelManager.shutdown()
        callbackDispatcher.clearListeners()
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
            SttLogger.error("code=INTEGRATION_ERROR, message=\"startProcessor(): controller is null — call initStt() first\"")
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
            pipelineStartMs = sessionController.utteranceElapsedMs(),
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

}
