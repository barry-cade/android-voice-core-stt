package dev.barrycade.voicecore.stt

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Main entry point for the STT pipeline.
 *
 * Configuration is via [SttRunConfig]:
 * 1. Call [setConfig] with a validated [SttRunConfig].
 * 2. Call [startSession] to begin recording and transcription.
 * 3. Use [stopAndTranscribe] to stop manually.
 * 4. Call [resetForNextSession] to reuse this instance for a new utterance.
 * 5. Call [destroy] to release all resources.
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
 * Lifecycle methods ([setConfig], [startSession], [stopAndTranscribe], [destroy],
 * [resetForNextSession]) are not thread-safe. Callers must serialise calls to
 * these methods.
 */
class SpeechToText internal constructor(
    private val config: RuntimeSttConfig,
    modelPath: String,
    private val whisperModel: WhisperModel = WhisperBridge,
    private val captureManager: SessionManager = CaptureManager()
) {
    companion object {
        /**
         * Create a [SpeechToText] with a minimal default configuration.
         *
         * The caller must call [setConfig] with a [SttRunConfig] before
         * calling [startSession].
         *
         * Model loading and warm-up begin immediately in the constructor.
         * Audio capture does NOT start until [startSession] is called
         * (CaptureManager begins lazily in [CaptureManager.beginPcmCapture]).
         *
         * @param modelPath Absolute path to the Whisper model binary.
         * @return A new [SpeechToText] instance.
         */
        fun create(modelPath: String): SpeechToText {
            return SpeechToText(
                config = RuntimeSttConfig(),
                modelPath = modelPath
            )
        }
    }

    /**
     * Debug/test options. Set via [setDebugOptions].
     */
    internal data class DebugOptions(
        val forceAudioInitFailure: Boolean = false,
        val forceTimeout: Boolean = false
    )

    internal var debugOptions: DebugOptions = DebugOptions()

    private var sttErrorListener: SttErrorListener? = null
    private var onResult: ((String) -> Unit)? = null
    private var onResultWithTiming: ((text: String, code: SttReturnCode, timing: SttTimingSnapshot?) -> Unit)? = null
    private var onError: ((Throwable) -> Unit)? = null

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

    private val isRunning = AtomicBoolean(false)
    private val isInferencing = AtomicBoolean(false)
    private val stateLock = Any()

    /** Thread-safe lifecycle state machine with internal lock. */
    internal val stateMachine = SttLifecycleStateMachine()

    private val modelManager: ModelManager

    /** [SttRunConfig] set via [setConfig]. */
    private var runConfig: SttRunConfig? = null

    /** DrainMode from the active [SttRunConfig]. */
    private var currentDrainMode: DrainMode = DrainMode.DRAIN_FROM_NEXT_FRAME

    /** Observable events for start/stop strategies. */
    private val events: SttEvents = SttEvents()

    /** VAD instance used during the active session (created by createProcessor). */
    @Volatile
    private var activeVad: Vad? = null

    /** Session start wall time (ms), used by stop strategies for elapsedMs. */
    private var sessionStartMs: Long = 0L

    // ── Session-scoped state (reset by resetForNextSession) ────────────────
    private var processorController: PollingController? = null
    @Volatile private var stopRequested: Boolean = false
    private var timingPcmStartMs: Long = 0L
    private var timingPcmTotalMs: Long = 0L
    private var timingUtteranceStartMs: Long = 0L

    private fun resetTiming() {
        timingPcmStartMs = 0L
        timingPcmTotalMs = 0L
        timingUtteranceStartMs = 0L
    }

    /**
     * Tracks whether [startSession] was called before model readiness.
     * When warm-up completes and this flag is set, [start] is called
     * automatically from the init callback.
     */
    private var startRequested: Boolean = false

    init {
        config.validate()

        // ── Pre-model warm-up (before first PCM frame is processed) ──────
        if (config.warmupEnabled) {
            whisperModel.warmup(config.warmupDurationMs)
        }

        // Transition to INITIALISED (CaptureManager pre-wired, mic NOT running).
        stateMachine.forceSet(SttLifecycleState.INITIALISED)

        modelManager = ModelManager(
            modelPath = modelPath,
            sttErrorListener = null,
            readyListener = null,
            whisperModel = whisperModel
        )
        // Start model loading and warm-up immediately.
        // When warm-up completes, the callback transitions to READY.
        // If startSession() was already called before readiness, it replays.
        modelManager.initAsync {
            synchronized(stateLock) {
                if (stateMachine.currentState is SttLifecycleState.INITIALISED) {
                    stateMachine.transitionTo(SttLifecycleState.READY)
                    // If startSession() queued a start, replay it now.
                    // beginPcmCapture() was already called in startSession()
                    // before the queue; the processor start path depends on mode.
                    if (startRequested) {
                        startRequested = false
                        if (isManualMode()) {
                            startMinimalProcessor()
                        } else {
                            captureManager.beginSttProcessing()
                            start()
                        }
                    }
                } else {
                    SttLogger.lifecycle("warm-up callback skipped: state=${stateMachine.currentState}")
                }
            }
        }
    }

    // ------- Public API ------------------------------------------------

    /**
     * Register a listener for transcription results.
     *
     * **Delivery thread:** Internal worker thread (processor or whisper executor).
     * Callers must post to [android.os.Handler] or [kotlinx.coroutines.Dispatchers.Main]
     * if main-thread delivery is required.
     */
    fun setOnResultListener(l: (String) -> Unit) {
        onResult = l
    }

    /**
     * Register a listener for transcription results with timing snapshot.
     *
     * **Delivery thread:** Same as [setOnResultListener].
     * The [SttTimingSnapshot] is non-null when the transcript was produced by
     * a full pipeline run (VAD + accumulator + inference). It is null during
     * early stop paths that bypass the accumulator.
     */
    fun setOnResultWithTimingListener(l: (text: String, code: SttReturnCode, timing: SttTimingSnapshot?) -> Unit) {
        onResultWithTiming = l
    }

    /**
     * Register a generic error listener.
     *
     * **Delivery thread:** The thread that encountered the error.
     * This may be a processor worker thread, the whisper executor, or a
     * caller thread (e.g. [stopAndTranscribe]). Must handle cross-thread
     * delivery safely.
     */
    fun setOnErrorListener(l: (Throwable) -> Unit) {
        onError = l
    }

    /**
     * Register a structured STT error listener.
     *
     * **Delivery thread:** Same as [setOnErrorListener].
     * [SttError] provides structured error metadata including category,
     * code, message, cause, and a context map.
     */
    fun setSttErrorListener(l: SttErrorListener) {
        sttErrorListener = l
    }

    /**
     * Set debug/test options for the pipeline.
     *
     * **Delivery thread:** Caller thread (no callback involved).
     *
     * @param forceAudioInitFailure  If true, audio capture initialisation will
     *                               fail immediately (for test error paths).
     * @param forceWhisperLoadFailure If true, whisper model load will fail
     *                                (for test error paths).
     * @param forceTimeout           If true, the accumulator will force a
     *                               timeout finalisation during tests.
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
     *
     * Validates [config] deterministically via [SttRunConfigValidator].
     * On failure, returns [SessionResult] with [SttReturnCode.INVALID_CONFIG].
     * On success, stores [config] internally and returns [SessionResult]
     * with [SttReturnCode.SUCCESS].
     * Does NOT start recording. Call [startSession] after this.
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
     *
     * Performs INITIALISATION ONLY:
     * 1. Loads the Whisper model synchronously via [ModelManager.loadModelIfNeeded].
     * 2. Runs mandatory warm-up inference.
     * 3. Constructs STT scaffolding selectively based on mode
     *    (ManualStart+ManualStop vs AutoStart/AutoStop).
     * 4. Configures the active start and stop strategies.
     *
     * After [initStt] completes successfully:
     * - Whisper model is loaded and warm.
     * - STT scaffolding is constructed according to the selected mode.
     * - Strategies are configured.
     * - No PCM capture is running.
     * - No STT processing is running.
     * - System is fully ready for the selected strategy to activate
     *   the required modules later.
     *
     * MUST be called after [setConfig]. If model loading fails, returns
     * [SttReturnCode.ENGINE_ERROR].
     *
     * @param config The [SttRunConfig] to initialise with.
     * @return [SessionResult] with [SttReturnCode.SUCCESS] on success,
     *         or an error code on failure.
     */
    fun initStt(config: SttRunConfig): SessionResult {
        // ── Step 0: Validate config ───────────────────────────────────────
        val validationResult = SttRunConfigValidator.validate(config)
        if (validationResult != null) {
            return validationResult
        }
        runConfig = config
        currentDrainMode = config.drainMode

        // ── Step 1: Load Whisper model ────────────────────────────────────
        if (!modelManager.loadModelIfNeeded()) {
            return SessionResult(SttReturnCode.ENGINE_ERROR, null)
        }

        // ── Step 2: Mandatory warm-up ─────────────────────────────────────
        modelManager.runWarmup(config.warmupDurationMs)

        // ── Step 3: Construct STT scaffolding selectively based on mode ───
        // Determine whether this is Manual mode (ManualStart + ManualStop)
        // or Auto mode (anything involving VAD/auto behaviour).
        val isManualMode = config.startStrategy.type.uppercase() == "MANUAL" &&
            config.stopStrategy.type.uppercase() == "MANUAL"

        // CaptureManager is always constructed (pre-wired in constructor).
        // ProcessorController is always needed for PCM polling.
        // VAD, accumulator, and utterance lifecycle are only needed in
        // auto modes where VAD-based decisions drive start/stop.
        if (isManualMode) {
            // Manual mode: CaptureManager + Processor only.
            // No VAD, no accumulator, no drain-mode components.
            // These are constructed lazily when startSession() is called.
            SttLogger.lifecycle("initStt: Manual mode selected — minimal scaffolding")
        } else {
            // Auto mode: VAD, accumulator, and utterance lifecycle
            // are constructed and pre-wired for the strategy to use.
            // Construction does NOT activate any behaviours.
            SttLogger.lifecycle("initStt: Auto mode selected — full scaffolding")
        }

        // ── Step 4: Configure strategies ──────────────────────────────────
        // Strategies are derived from config at runtime via RuntimeSttConfig.
        // They are NOT invoked during initStt().
        val runtimeConfig = RuntimeSttConfig.fromSttRunConfig(config)
        SttLogger.config("initStt: startStrategy=${runtimeConfig.startStrategy::class.simpleName}, " +
            "stopStrategy=${runtimeConfig.stopStrategy::class.simpleName}")

        return SessionResult(SttReturnCode.SUCCESS, null)
    }

    /**
     * Start an STT session using the config previously set via [setConfig].
     *
     * Start/stop is driven by the [SttRunConfig] strategies:
     * - [Config.startStrategy] defines when capture begins.
     * - [Config.stopStrategy] defines when capture ends.
     *
     * Manually raised events (via [startSession] and [stopAndTranscribe])
     * are consumed by [ManualStart] and [ManualStop] strategies respectively.
     *
     * Lifecycle ordering (Phase 3):
     * 1. Strategy approval via [Config.startStrategy].
     * 2. PCM capture begins ([CaptureManager.beginPcmCapture]) — first gate.
     * 3. Model readiness check (model loaded async in constructor).
     * 4. STT pipeline starts ([CaptureManager.beginSttProcessing] + processor).
     *
     * If the model is still warming up, the start is queued and replayed
     * when model readiness completes.
     *
     * ## Return codes
     *
     * | [SttReturnCode.CONFIG_NOT_SET] | [setConfig] was not called. |
     * | [SttReturnCode.SUCCESS] | Session started successfully (or queued). |
     * | [SttReturnCode.ENGINE_ERROR] | Internal pipeline error. |
     */
    fun startSession(): SessionResult {
        val storedConfig = runConfig
        if (storedConfig == null) {
            return SessionResult(SttReturnCode.CONFIG_NOT_SET, null)
        }

        synchronized(stateLock) {
            if (stateMachine.currentState is SttLifecycleState.READY ||
                stateMachine.currentState is SttLifecycleState.INITIALISED
            ) {
                // Raise the manual start event for the start strategy.
                events.manualStartPressed.raise()

                // Evaluate the start strategy. For manual start, shouldStart
                // consumes the raised event. For VAD_START/WAKEWORD, the
                // event is consumed by the respective strategy.
                if (!config.startStrategy.shouldStart(events, activeVad)) {
                    return SessionResult(SttReturnCode.SUCCESS, null)
                }

                // ── PCM starts — first gate after strategy approval ─────
                // CaptureManager is pre-wired. beginPcmCapture() starts
                // AudioCapture synchronously (AudioRecord.startRecording())
                // and clears the session buffer. This must complete before
                // STT pipeline initialisation.
                sessionStartMs = System.currentTimeMillis()
                captureManager.beginPcmCapture()

                if (stateMachine.currentState is SttLifecycleState.READY) {
                    // Model is ready. Start the processor (minimal or full
                    // depending on mode), then begin the drain thread only
                    // in auto modes where it is needed.
                    if (isManualMode()) {
                        // Manual mode: no VAD, no accumulator, no drain thread.
                        // The minimal processor just polls frames to prevent
                        // unbounded queue growth while stop is awaited.
                        startMinimalProcessor()
                    } else {
                        // Auto mode: drain thread + full STT pipeline.
                        captureManager.beginSttProcessing()
                        start()
                    }
                } else {
                    // Model still warming up. Queue start request.
                    // The start path will be chosen when the model becomes
                    // ready and the replay occurs.
                    startRequested = true
                }
            } else {
                // Illegal state — log and return error.
                SttLogger.lifecycleW("startSession() called from ${stateMachine.currentState} -- ignoring")
                return SessionResult(SttReturnCode.ENGINE_ERROR, null)
            }
        }

        return SessionResult(SttReturnCode.SUCCESS, null)
    }

    /**
     * Stop the current session and transcribe accumulated audio.
     *
     * This method:
     * 1. Calls [CaptureManager.finalize] to drain all buffered PCM immediately.
     *    No VAD, no accumulator — raw PCM concatenation.
     * 2. Submits inference to the Whisper executor (queues behind warm-up if needed).
     * 3. Transitions to STOPPED.
     *
     * Acceptable states:
     * - [SttLifecycleState.RECORDING]: normal stop during active capture.
     * - [SttLifecycleState.READY] or [SttLifecycleState.INITIALISED]: stop during
     *   warm-up; PCM accumulated since [begin] is returned.
     * - [SttLifecycleState.FINALISING]: already stopping, allow re-entry (no-op).
     *
     * States UNINITIALISED and STOPPED are ignored.
     */
    fun stopAndTranscribe() {
        val elapsedMs = if (sessionStartMs > 0) {
            (System.currentTimeMillis() - sessionStartMs).toInt()
        } else {
            0
        }

        synchronized(stateLock) {
            SttLogger.pcm("[STOP] entered -- isRunning=${isRunning.get()}, state=${stateMachine.currentState}")

            // Raise the manual stop event for the stop strategy.
            events.manualStopPressed.raise()

            // Evaluate the stop strategy using observable events and VAD.
            if (!config.stopStrategy.shouldStop(events, activeVad, elapsedMs)) {
                return
            }

            if (stateMachine.currentState is SttLifecycleState.STOPPED) {
                SttLogger.pcm("[STOP] ignoring -- state=STOPPED")
                return
            }

            if (stateMachine.currentState is SttLifecycleState.FINALISING) {
                SttLogger.pcm("[STOP] already FINALISING -- returning")
                return
            }

            // ── Finalise PCM via CaptureManager (raw PCM, no VAD) ────────
            isRunning.set(false)
            stopRequested = true

            // Stop the processor thread if it was running.
            processorController?.stop()
            processorController = null

            // Extract PCM before stopping capture.
            val finalPcm = captureManager.finalize()

            // Stop capture — microphone off until restartCapture.
            captureManager.stopCapture()

            if (finalPcm.isEmpty()) {
                SttLogger.pcm("[STOP] no PCM accumulated -- transitioning to STOPPED")
                stateMachine.transitionTo(SttLifecycleState.STOPPED)
                return
            }

            // Transition to FINALISING (inference pending).
            if (stateMachine.currentState is SttLifecycleState.RECORDING ||
                stateMachine.currentState is SttLifecycleState.READY ||
                stateMachine.currentState is SttLifecycleState.INITIALISED
            ) {
                stateMachine.forceSet(SttLifecycleState.FINALISING)
            }

            val timingMs = if (timingPcmStartMs > 0) {
                System.currentTimeMillis() - timingPcmStartMs
            } else {
                0L
            }

            // ═════════════════════════════════════════════════════════════
            // Phase 2: Submit inference (outside stateLock)
            // ═════════════════════════════════════════════════════════════
            submitInferenceAndDispatch(
                pcm = finalPcm,
                code = SttReturnCode.SUCCESS,
                vadActiveMs = 0L,
                utteranceMs = 0L,
                captureMs = timingMs
            )
        }

        // Transition to STOPPED after inference is submitted.
        synchronized(stateLock) {
            if (stateMachine.currentState is SttLifecycleState.FINALISING) {
                stateMachine.transitionTo(SttLifecycleState.STOPPED)
            }
        }
    }

    fun stop() = stopAndTranscribe()

    /**
     * Reset this instance for a new session without unloading the model.
     *
     * Call this after [stopAndTranscribe] has delivered its result to prepare
     * for a new utterance. The model stays loaded and warm, so the next
     * [startSession] call will begin capture immediately without warm-up.
     *
     * CaptureManager is NOT stopped — only the session buffer is reset.
     * AudioCapture continues running (invarant #6).
     *
     * Safe to call multiple times. Idempotent when no session is active.
     */
    fun resetForNextSession() {
        synchronized(stateLock) {
            SttLogger.lifecycle("resetForNextSession: state=${stateMachine.currentState}")
            processorController?.stop()
            processorController = null
            activeVad = null
            sessionStartMs = 0L
            // Restart AudioCapture (was stopped by finalize() during stopAndTranscribe).
            captureManager.restartCapture()
            captureManager.reset()
            isRunning.set(false)
            stopRequested = false
            startRequested = false
            resetTiming()
            if (stateMachine.currentState is SttLifecycleState.RECORDING ||
                stateMachine.currentState is SttLifecycleState.FINALISING ||
                stateMachine.currentState is SttLifecycleState.STOPPED
            ) {
                // Model is still warm — go back to READY.
                stateMachine.forceSet(SttLifecycleState.READY)
            }
        }
    }

    // ------- destroy() ------------------------------------------------

    fun destroy() {
        synchronized(stateLock) {
            processorController?.stop()
            processorController = null
            activeVad = null
            captureManager.shutdown()
            isRunning.set(false)
            stopRequested = false
            startRequested = false
            resetTiming()
            modelManager.unload()
            if (stateMachine.currentState is SttLifecycleState.RECORDING ||
                stateMachine.currentState is SttLifecycleState.FINALISING
            ) {
                stateMachine.transitionTo(SttLifecycleState.STOPPED)
            }
            stateMachine.forceSet(SttLifecycleState.UNINITIALISED)
        }
        modelManager.shutdown()
    }

    // ======== Internal pipeline ========================================

    /**
     * Returns true when the active mode is ManualStart + ManualStop.
     *
     * In Manual mode, only CaptureManager and a minimal processor are used.
     * VAD, accumulator, drain thread, and utterance lifecycle are bypassed.
     */
    private fun isManualMode(): Boolean {
        val storedConfig = runConfig ?: return false
        val startType = storedConfig.startStrategy.type.uppercase()
        val stopType = storedConfig.stopStrategy.type.uppercase()
        return startType == "MANUAL" && stopType == "MANUAL"
    }

    /**
     * Test helper: bypass the start strategy and trigger start directly.
     * Used by unit tests to simulate start without raising events.
     */
    internal fun processStart() {
        synchronized(stateLock) {
            if (stateMachine.currentState is SttLifecycleState.READY) {
                start()
            }
        }
    }

    internal fun start() {
        synchronized(stateLock) {
            if (isRunning.get()) return

            if (!modelManager.isReady) {
                SttLogger.lifecycleW("start() called before model ready -- ignoring")
                return
            }
            if (modelManager.initFailed) {
                dispatchError(RuntimeException("Model initialisation failed"))
                return
            }
            if (debugOptions.forceAudioInitFailure) {
                dispatchError(RuntimeException("Forced test: AudioCapture init"))
                return
            }
            if (stateMachine.currentState !is SttLifecycleState.READY &&
                stateMachine.currentState !is SttLifecycleState.INITIALISED
            ) {
                SttLogger.lifecycleW("start() called from ${stateMachine.currentState} -- ignoring")
                return
            }

            resetTiming()
            SttLogger.config("Active config: $config")

            if (!stateMachine.transitionTo(SttLifecycleState.RECORDING)) {
                return
            }

            timingPcmStartMs = System.currentTimeMillis()

            // CaptureManager is already running. Construct the full STT pipeline
            // with VAD, accumulator, and utterance lifecycle (auto mode).
            val processor = createProcessor(captureManager)

            processorController = processor
            processor.start()
            timingUtteranceStartMs = System.currentTimeMillis()
            isRunning.set(true)
        }
    }

    /**
     * Start a minimal processor for ManualStart + ManualStop mode.
     *
     * No VAD, no accumulator, no utterance lifecycle. The minimal processor
     * polls PCM frames from CaptureManager to prevent unbounded queue growth
     * while awaiting an explicit stop request. Frames are buffered into the
     * session by [CaptureManager.pollFrame] and returned via [CaptureManager.finalize]
     * when stop is requested.
     *
     * Must be called from within [stateLock].
     */
    private fun startMinimalProcessor() {
        if (isRunning.get()) return

        if (!modelManager.isReady) {
            SttLogger.lifecycleW("startMinimalProcessor() called before model ready -- ignoring")
            return
        }
        if (modelManager.initFailed) {
            dispatchError(RuntimeException("Model initialisation failed"))
            return
        }
        if (debugOptions.forceAudioInitFailure) {
            dispatchError(RuntimeException("Forced test: AudioCapture init"))
            return
        }
        if (stateMachine.currentState !is SttLifecycleState.READY &&
            stateMachine.currentState !is SttLifecycleState.INITIALISED
        ) {
            SttLogger.lifecycleW("startMinimalProcessor() called from ${stateMachine.currentState} -- ignoring")
            return
        }

        resetTiming()
        SttLogger.config("Active config: $config (Manual mode)")

        if (!stateMachine.transitionTo(SttLifecycleState.RECORDING)) {
            return
        }

        timingPcmStartMs = System.currentTimeMillis()

        // Mark PCM capture as active so frames are accepted by CaptureManager's
        // drain-thread guard (even though no drain thread is started in Manual mode).
        captureManager.activatePcmCapture()

        // Minimal processor: polls frames to prevent queue bloat.
        // No VAD, no accumulator, no utterance lifecycle.
        val processor = createMinimalProcessor(captureManager)

        processorController = processor
        processor.start()
        timingUtteranceStartMs = System.currentTimeMillis()
        isRunning.set(true)
    }

    private fun createProcessor(capture: AudioSource): PollingController {
        val vad = Vad(config)
        vad.debugLogging = config.debugLoggingEnabled
        activeVad = vad

        val accumulator = UtteranceAccumulator(
            config
        )
        accumulator.sttErrorListener = this@SpeechToText.sttErrorListener
        if (debugOptions.forceTimeout) {
            accumulator.forceTimeout = true
        }
        accumulator.onSpeechStart = {
            processorController?.resetVadActiveMs()
        }

        val utteranceHandler = UtteranceHandler()

        val processor = ProcessorController(
            audioSource = capture,
            vad = vad,
            utteranceAccumulator = accumulator,
            listener = utteranceHandler,
            sampleRate = 16000,
            debugLogging = config.debugLoggingEnabled,
            stopRequestedRef = { this@SpeechToText.stopRequested }
        )
        return processor
    }

    /**
     * Create a minimal processor for ManualStart + ManualStop mode.
     *
     * No VAD, no accumulator, no utterance lifecycle, no drain thread.
     * Returns a [MinimalPollingController] that polls frames from the
     * [AudioSource] to prevent unbounded queue growth, without performing
     * any VAD, accumulator, or utterance lifecycle processing. When stop
     * is requested, [CaptureManager.finalize] returns the accumulated PCM
     * for Whisper inference.
     */
    private fun createMinimalProcessor(capture: AudioSource): PollingController {
        val controller = MinimalPollingController(
            audioSource = capture,
            stopRequestedRef = { this@SpeechToText.stopRequested }
        )
        return controller
    }

    /**
     * Minimal polling controller for ManualStart + ManualStop mode.
     *
     * Polls PCM frames from [audioSource] in a loop, discarding them
     * from the AudioCapture queue to prevent unbounded growth. No VAD,
     * no accumulator, no utterance lifecycle — frames are buffered into
     * the session by [CaptureManager.pollFrame] and returned via
     * [CaptureManager.finalize] when stop is requested.
     *
     * Exposes [stop] to halt the polling thread, [start] to begin polling,
     * and [vadActiveMs]/[lastUtteranceDurationMs] for protocol compatibility
     * with [ProcessorController] (both report 0).
     */
    private class MinimalPollingController(
        private val audioSource: AudioSource,
        private val stopRequestedRef: () -> Boolean
    ) : PollingController {

        @Volatile
        private var isRunning: Boolean = false

        @Volatile
        override var vadActiveMs: Long = 0L

        @Volatile
        override var lastUtteranceDurationMs: Int = 0

        override var vadConfidence: Float = 0f

        private var workerThread: Thread? = null

        override fun start() {
            if (isRunning) return
            isRunning = true

            val runnable = Runnable {
                while (isRunning) {
                    if (stopRequestedRef()) {
                        try { Thread.sleep(10L) } catch (_: InterruptedException) { break }
                        continue
                    }
                    val frame = audioSource.pollFrame()
                    if (frame == null) {
                        try { Thread.sleep(10L) } catch (_: InterruptedException) { break }
                    }
                }
            }
            val thread = Thread(runnable, "MinimalPollingThread")
            workerThread = thread
            thread.start()
        }

        override fun stop() {
            if (!isRunning) return
            isRunning = false
            workerThread?.join(500)
            workerThread = null
        }

        override fun resetVadActiveMs() {
            // No-op: VAD is not used in this controller.
        }

        override fun drainRemainingFrames(): FloatArray? = null
        override fun stopAndFinalize(): FloatArray? = null
        override val rmsSampler: RmsSampler
            get() = RmsSampler(16000, debugLogging = false) { _, _, _ -> }
    }

    /**
     * Submit an inference task to the whisper executor.
     *
     * Converts [pcm] to ShortArray on the caller thread (fast), then submits
     * the blocking transcribe() call to the whisper executor so the caller
     * (processor thread or stop thread) is not blocked for the duration of
     * inference.
     *
     * Timing capture (whisperMs, totalMs) happens on the executor thread.
     * Result dispatch (onResult, onResultWithTiming, onTimingListener) also
     * occurs on the executor thread.
     */
    private fun submitInferenceAndDispatch(
        pcm: FloatArray,
        code: SttReturnCode,
        vadActiveMs: Long,
        utteranceMs: Long,
        captureMs: Long
    ) {
        val shortPcm = pcm.toShortArray()
        val pipelineStartMs = if (timingUtteranceStartMs > 0) timingUtteranceStartMs else System.currentTimeMillis()
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

            onTimingListener?.invoke(captureMs, vadActiveMs, whisperMs, totalMs)
            dispatchResult(text, code, snapshot)
        }

        modelManager.submitInference(shortPcm, onResultCallback)
    }

    private fun dispatchResult(text: String, code: SttReturnCode, timing: SttTimingSnapshot?) {
        onResultWithTiming?.invoke(text, code, timing)
        onResult?.invoke(text)
    }

    private fun dispatchError(t: Throwable) {
        onError?.invoke(t)
        sttErrorListener?.onSttError(
            SttError(
                SttErrorCategory.UNKNOWN,
                SttErrorCode.INTERNAL_EXCEPTION,
                t.message ?: "Unknown error",
                cause = t
            )
        )
    }

    private fun FloatArray.toShortArray(): ShortArray {
        val shorts = ShortArray(size)
        for (i in indices) {
            shorts[i] = (kotlin.math.max(-1f, kotlin.math.min(1f, this[i])) * Short.MAX_VALUE)
                .toInt()
                .toShort()
        }
        return shorts
    }

    private inner class UtteranceHandler : UtteranceListener {
        override fun onUtteranceReady(pcm: FloatArray, code: SttReturnCode) {
            if (!isRunning.get()) return
            if (!isInferencing.compareAndSet(false, true)) return

            try {
                val vadMs = processorController?.vadActiveMs ?: 0L
                val utterMs = (processorController?.lastUtteranceDurationMs ?: 0).toLong()
                submitInferenceAndDispatch(pcm, code, vadMs, utterMs, timingPcmTotalMs)
            } finally {
                isInferencing.set(false)
            }
        }
    }
}
