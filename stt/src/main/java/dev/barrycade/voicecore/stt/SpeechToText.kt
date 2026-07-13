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
 * 1. Call [setConfig] with a validated [SttRunConfig].
 * 2. Call [initStt] once to load the model, run warm-up, and build scaffolding.
 * 3. Call [startSession] to begin recording and transcription.
 * 4. Use [stopAndTranscribe] to stop manually.
 * 5. Call [resetForNextSession] to reuse this instance for a new utterance.
 * 6. Call [destroy] to release all resources (app shutdown).
 *
 * ## Threading
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
 * Lifecycle methods ([setConfig], [initStt], [startSession], [stopAndTranscribe],
 * [destroy], [resetForNextSession]) are serialized internally via [stateLock].
 */
class SpeechToText internal constructor(
    context: Context?,
    private val whisperModel: WhisperModel = WhisperBridge,
    captureManager: SessionManager = CaptureManager()
) {

    // ── Controller references ────────────────────────────────────────────

    /** Capture lifecycle controller, wraps the [SessionManager]. */
    internal val captureController = SttCaptureController(captureManager)

    internal val lifecycleController = SttLifecycleController()
    internal val sessionController = SttSessionController()
    internal val modeController = SttModeController()
    internal val threadController = SttThreadController()
    internal val callbackDispatcher = SttCallbackDispatcher()

    // ── Debug options (retained as inner data class for backward compat) ─

    /**
     * Debug/test options. Set via [setDebugOptions].
     */
    internal data class DebugOptions(
        val forceAudioInitFailure: Boolean = false,
        val forceTimeout: Boolean = false
    )

    internal var debugOptions: DebugOptions = DebugOptions()

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

    /** True once [initStt] has completed successfully. Idempotency guard. */
    private var isInitialised: Boolean = false

    /** Model manager — created once, persists across utterances. */
    internal val modelManager: ModelManager

    /** Dedicated inference adapter controller. */
    internal val inferenceController: SttInferenceController

    /** Processing controller for Auto mode. null in Manual mode. */
    internal var processingController: SttProcessingController? = null

    /** [SttRunConfig] set via [setConfig]. */
    private var runConfig: SttRunConfig? = null

    /** Runtime config derived from [SttRunConfig]. */
    private var config: RuntimeSttConfig = RuntimeSttConfig()

    /** DrainMode from the active [SttRunConfig]. */
    private var currentDrainMode: DrainMode = DrainMode.DRAIN_FROM_NEXT_FRAME

    /** Observable events for start/stop strategies. */
    private val events: SttEvents = SttEvents()

    init {
        modelManager = ModelManager(
            modelPath = "",
            sttErrorListener = null,
            readyListener = null,
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
        this.debugOptions = DebugOptions(
            forceAudioInitFailure = forceAudioInitFailure,
            forceTimeout = forceTimeout
        )
        modelManager.forceWhisperLoadFailure = forceWhisperLoadFailure
    }

    /**
     * Set the [SttRunConfig] for a subsequent [startSession] call.
     */
    fun setConfig(config: SttRunConfig): SessionResult {
        synchronized(stateLock) {
            val validationResult = SttRunConfigValidator.validate(config)
            if (validationResult != null) {
                return validationResult
            }
            runConfig = config
            currentDrainMode = config.drainMode
            return SessionResult(SttReturnCode.SUCCESS, null)
        }
    }

    /**
     * Initialise the STT system for the given [config] without activating
     * any STT processing behaviours.
     */
    fun initStt(config: SttRunConfig): SessionResult {
        synchronized(stateLock) {
            // ── Idempotency guard: already initialised or in READY state ─────────
            if (isInitialised || lifecycleController.currentState is SttLifecycleState.READY) {
                SttLogger.lifecycle("initStt: already initialised — returning SUCCESS immediately")
                return SessionResult(SttReturnCode.SUCCESS, null)
            }

            // ── Step 0: Validate config ───────────────────────────────────────
            val validationResult = SttRunConfigValidator.validate(config)
            if (validationResult != null) {
                return validationResult
            }
            runConfig = config
            currentDrainMode = config.drainMode

            // ── Step 1: Update model path and load model ──────────────────────
            modelManager.updateModelPath(config.ttsEngineConfig.modelPath)

            if (!modelManager.loadModelIfNeeded()) {
                return SessionResult(SttReturnCode.ENGINE_ERROR, null)
            }

            // ── Step 2: Mandatory warm-up (once per app lifetime) ─────────────
            if (config.warmupEnabled) {
                modelManager.runWarmup(config.warmupDurationMs)
            }

            // ── Step 3: Build runtime config ──────────────────────────────────
            this.config = RuntimeSttConfig.fromSttRunConfig(config)

            // ── Step 3a: Reconstruct CaptureManager with runtime buffer size ──
            // If the default CaptureManager was created in the constructor (no
            // test double injected), replace it with one configured for the
            // runtime bufferSizeSamples.
            val currentSessionManager = captureController.sessionManager
            if (currentSessionManager is CaptureManager) {
                captureController.sessionManager = CaptureManager(
                    bufferSizeSamples = config.bufferSizeSamples
                )
            }

            val sessionManager = captureController.sessionManager

            // ── Step 4: Construct STT scaffolding via mode controller ─────────
            modeController.selectController(
                config = this.config,
                captureManager = sessionManager,
                stopRequestedRef = { this.stopRequested }
            )

            if (!modeController.isManualMode()) {
                processingController = SttProcessingController(
                    config = this.config,
                    captureManager = sessionManager,
                    stopRequestedRef = { this.stopRequested },
                    sttErrorListener = callbackDispatcher.getSttErrorListener(),
                    forceTimeout = debugOptions.forceTimeout,
                    listener = ProcessingListener { pcm, code ->
                        handleUtteranceReady(pcm, code)
                    }
                )
            }

            // ── Step 5: Mark initialised ──────────────────────────────────────
            isInitialised = true
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
            if (runConfig == null) {
                return SessionResult(SttReturnCode.CONFIG_NOT_SET, null)
            }

            if (!isInitialised && lifecycleController.currentState !is SttLifecycleState.READY) {
                SttLogger.lifecycleW("startSession() called before initStt() — returning CONFIG_NOT_SET")
                return SessionResult(SttReturnCode.CONFIG_NOT_SET, null)
            }

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

            if (!config.startStrategy.shouldStart(events, processingController?.vad)) {
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
            val elapsedMs = sessionController.endSession().toInt()

            SttLogger.pcm("[STOP] entered -- isRunning=${isRunning.get()}, state=${lifecycleController.currentState}")

            events.manualStopPressed.raise()

            if (!config.stopStrategy.shouldStop(events, processingController?.vad, elapsedMs)) {
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
            stopRequested = true

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
            stopRequested = false
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
            stopRequested = false
            isInferencing.set(false)
            currentSessionEpoch = 0L
            sessionEpoch.incrementAndGet()
            sessionController.resetUtteranceTiming()
            transitionPipelineToIdleLocked("destroy")
            modelManager.unload()
            isInitialised = false
            lifecycleController.onDestroy()
        }
        modelManager.shutdown()
        callbackDispatcher.clearListeners()
    }

    // ======== Internal pipeline ========================================

    /** @deprecated Use [lifecycleController] instead. */
    @Deprecated("Use lifecycleController.currentState")
    internal val stateMachine: SttLifecycleStateMachine
        get() {
            val bridge = SttLifecycleStateMachine()
            bridge.forceSet(lifecycleController.currentState)
            return bridge
        }

    /**
     * Test helper: bypass the start strategy and trigger start directly.
     */
    internal fun processStart() {
        synchronized(stateLock) {
            if (lifecycleController.canStartSession()) {
                startProcessor()
            }
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
        if (debugOptions.forceAudioInitFailure) {
            callbackDispatcher.dispatchError(RuntimeException("Forced test: AudioCapture init"))
            return
        }
        if (!lifecycleController.canStartSession()) {
            SttLogger.lifecycleW("startProcessor() called from ${lifecycleController.currentState} -- ignoring")
            return
        }

        sessionController.resetUtteranceTiming()
        SttLogger.config("Active config: $config")

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

    @Volatile private var stopRequested: Boolean = false

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
                synchronized(stateLock) {
                    isInferencing.set(false)
                    if (isRunning.get()) {
                        transitionPipelineStageLocked(SttPipelineStage.CAPTURING, "inference submit rejected")
                    } else {
                        transitionPipelineToIdleLocked("inference submit rejected")
                    }
                }
            }
        } catch (t: Throwable) {
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
        val request = SttInferenceController.InferenceRequest(
            pcm = pcm,
            code = code,
            vadActiveMs = vadActiveMs,
            utteranceMs = utteranceMs,
            captureMs = captureMs,
            preRollMs = config.preRollMs.toLong(),
            autoSilenceMs = config.autoSilenceMs.toLong(),
            pipelineStartMs = sessionController.utteranceElapsedMs(),
            sessionEpochAtSubmission = sessionEpochAtSubmission
        )

        return inferenceController.submit(
            request = request,
            decideDispatch = {
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
            },
            onPostDispatch = {
                if (completeStopPath) {
                    return@submit
                }

                synchronized(stateLock) {
                    if (sessionEpochAtSubmission != currentSessionEpoch) {
                        transitionPipelineToIdleLocked("dispatch stale completion")
                    } else if (isRunning.get()) {
                        transitionPipelineStageLocked(SttPipelineStage.CAPTURING, "dispatch complete")
                    } else {
                        transitionPipelineToIdleLocked("dispatch complete not running")
                    }
                }
            },
            onComplete = {
                synchronized(stateLock) {
                    isInferencing.set(false)

                    if (!completeStopPath) {
                        return@submit
                    }

                    if (sessionEpochAtSubmission != currentSessionEpoch) {
                        return@submit
                    }

                    transitionPipelineToIdleLocked("stop inference complete")
                    currentSessionEpoch = 0L
                    lifecycleController.onStop()
                    lifecycleController.onReset()
                }
            }
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

}
