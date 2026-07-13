package dev.barrycade.voicecore.stt

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

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
 * [destroy], [resetForNextSession]) are not thread-safe. Callers must serialise
 * calls to these methods.
 */
class SpeechToText internal constructor(
    context: Context?,
    private val whisperModel: WhisperModel = WhisperBridge,
    internal var captureManager: SessionManager = CaptureManager()
) {

    // ── Controller references ────────────────────────────────────────────

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

    /** Synchronisation lock for state transitions. */
    private val stateLock = Any()

    /** True once [initStt] has completed successfully. Idempotency guard. */
    private var isInitialised: Boolean = false

    /** Model manager — created once, persists across utterances. */
    internal val modelManager: ModelManager

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
        val validationResult = SttRunConfigValidator.validate(config)
        if (validationResult != null) {
            return validationResult
        }
        runConfig = config
        currentDrainMode = config.drainMode
        return SessionResult(SttReturnCode.SUCCESS, null)
    }

    /**
     * Initialise the STT system for the given [config] without activating
     * any STT processing behaviours.
     */
    fun initStt(config: SttRunConfig): SessionResult {
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
        if (captureManager is CaptureManager) {
            captureManager = CaptureManager(
                bufferSizeSamples = config.bufferSizeSamples
            )
        }

        // ── Step 4: Construct STT scaffolding via mode controller ─────────
        modeController.selectController(
            config = this.config,
            captureManager = captureManager,
            stopRequestedRef = { this.stopRequested },
            sttErrorListener = callbackDispatcher.getSttErrorListener(),
            forceTimeout = debugOptions.forceTimeout
        )

        // Wire the utterance-ready callback.
        modeController.onUtteranceReadyCallback = { pcm, code ->
            handleUtteranceReady(pcm, code)
        }

        // ── Step 5: Mark initialised ──────────────────────────────────────
        isInitialised = true
        lifecycleController.onReady()
        SttLogger.lifecycle("initStt: initialisation complete")

        return SessionResult(SttReturnCode.SUCCESS, null)
    }

    /**
     * Start an STT session using the config previously set via [setConfig].
     */
    fun startSession(): SessionResult {
        val storedConfig = runConfig
        if (storedConfig == null) {
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

        synchronized(stateLock) {
            if (lifecycleController.canStartSession()) {
                events.manualStartPressed.raise()

                if (!config.startStrategy.shouldStart(events, modeController.activeVad)) {
                    return SessionResult(SttReturnCode.SUCCESS, null)
                }

                sessionController.beginSession()
                captureManager.beginPcmCapture()

                if (modeController.isManualMode()) {
                    captureManager.activatePcmCapture()
                    modeController.minimalProcessorController?.start()
                } else {
                    captureManager.beginSttProcessing()
                    startProcessor()
                }
            } else {
                SttLogger.lifecycleW("startSession() called from ${lifecycleController.currentState} -- ignoring")
                return SessionResult(SttReturnCode.ENGINE_ERROR, null)
            }
        }

        return SessionResult(SttReturnCode.SUCCESS, null)
    }

    /**
     * Stop the current session and transcribe accumulated audio.
     */
    fun stopAndTranscribe() {
        val elapsedMs = sessionController.endSession().toInt()

        synchronized(stateLock) {
            SttLogger.pcm("[STOP] entered -- isRunning=${isRunning.get()}, state=${lifecycleController.currentState}")

            events.manualStopPressed.raise()

            if (!config.stopStrategy.shouldStop(events, modeController.activeVad, elapsedMs)) {
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

            // ── Finalise PCM via CaptureManager (raw PCM, no VAD) ────────
            isRunning.set(false)
            stopRequested = true

            modeController.stopController()

            val finalPcm = captureManager.finalize()

            captureManager.stopCapture()

            if (finalPcm.isEmpty()) {
                SttLogger.pcm("[STOP] no PCM accumulated -- transitioning to STOPPED then READY")
                lifecycleController.onStop()
                lifecycleController.onReset()
                return
            }

            // Transition to FINALISING (inference pending).
            lifecycleController.onFinalising()

            val timingMs = if (sessionController.timingPcmStartMs > 0) {
                System.currentTimeMillis() - sessionController.timingPcmStartMs
            } else {
                0L
            }

            // Phase 2: Submit inference (outside stateLock)
            submitInferenceAndDispatch(
                pcm = finalPcm,
                code = SttReturnCode.SUCCESS,
                vadActiveMs = 0L,
                utteranceMs = 0L,
                captureMs = timingMs
            )
        }

        // Transition to STOPPED after inference is submitted,
        // then immediately reset to READY for the next utterance.
        synchronized(stateLock) {
            lifecycleController.onStop()
            lifecycleController.onReset()
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

            sessionController.resetSession()
            captureManager.restartCapture()
            captureManager.reset()
            isRunning.set(false)
            stopRequested = false
            sessionController.resetUtteranceTiming()
            lifecycleController.onReset()
        }
    }

    // ------- destroy() ------------------------------------------------

    fun destroy() {
        synchronized(stateLock) {
            modeController.stopController()
            captureManager.shutdown()
            isRunning.set(false)
            stopRequested = false
            sessionController.resetUtteranceTiming()
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

        val controller = modeController.selectedController()

        if (controller == null) {
            SttLogger.error("code=INTEGRATION_ERROR, message=\"startProcessor(): controller is null — call initStt() first\"")
            return
        }

        controller.start()
        sessionController.beginUtteranceTiming()
        isRunning.set(true)
    }

    // ── Session-scoped state ─────────────────────────────────────────────

    @Volatile private var stopRequested: Boolean = false

    // ======== Inference and dispatch ======================================

    /**
     * Handle utterance-ready event from the mode controller's utterance handler.
     */
    private fun handleUtteranceReady(pcm: FloatArray, code: SttReturnCode) {
        if (!isRunning.get()) return
        if (!isInferencing.compareAndSet(false, true)) return

        try {
            val vadMs = modeController.vadActiveMs()
            val utterMs = modeController.lastUtteranceDurationMs().toLong()
            val captureMs = sessionController.captureMs()
            submitInferenceAndDispatch(pcm, code, vadMs, utterMs, captureMs)
        } finally {
            isInferencing.set(false)
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
        captureMs: Long
    ) {
        val shortPcm = pcm.toShortArray()
        val pipelineStartMs = sessionController.utteranceElapsedMs()
        val effectiveSilenceMs = config.autoSilenceMs.toLong()

        val onResultCallback: (String) -> Unit = { text ->
            val whisperMs = System.currentTimeMillis() - pipelineStartMs
            val totalMs = System.currentTimeMillis() - pipelineStartMs

            val snapshot = SttTimingSnapshot(
                vadActiveMs = vadActiveMs,
                utteranceDurationMs = utteranceMs,
                silencePaddingMs = effectiveSilenceMs,
                preRollMs = config.preRollMs.toLong(),
                inferenceMs = whisperMs,
                totalPipelineMs = totalMs
            )

            callbackDispatcher.dispatchTiming(captureMs, vadActiveMs, whisperMs, totalMs)
            callbackDispatcher.dispatchResult(text, code, snapshot)
        }

        modelManager.submitInference(shortPcm, onResultCallback)
    }

    // ======== Helpers =====================================================

    private fun FloatArray.toShortArray(): ShortArray {
        val shorts = ShortArray(size)
        for (i in indices) {
            shorts[i] = (kotlin.math.max(-1f, kotlin.math.min(1f, this[i])) * Short.MAX_VALUE)
                .toInt()
                .toShort()
        }
        return shorts
    }
}
