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
    internal val captureManager: SessionManager = CaptureManager()
) {

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

    /** VAD instance created once during [initStt]. null until initialised. */
    @Volatile
    private var activeVad: Vad? = null

    /** Accumulator created once during [initStt] for Auto mode. null until initialised. */
    private var utteranceAccumulator: UtteranceAccumulator? = null

    /** Processor controller created once during [initStt]. null until initialised. */
    private var processorController: PollingController? = null

    /** Minimal processor created once during [initStt] for Manual mode. null until initialised. */
    private var minimalProcessorController: PollingController? = null

    /** Utterance handler created once during [initStt]. null until initialised. */
    private var utteranceHandler: UtteranceHandler? = null

    /** True once [initStt] has completed successfully. */
    private var isInitialised: Boolean = false

    /** Session start wall time (ms), used by stop strategies for elapsedMs. */
    private var sessionStartMs: Long = 0L

    // ── Session-scoped state (reset by resetForNextSession) ────────────────
    @Volatile private var stopRequested: Boolean = false
    private var timingPcmStartMs: Long = 0L
    private var timingPcmTotalMs: Long = 0L
    private var timingUtteranceStartMs: Long = 0L

    private fun resetTiming() {
        timingPcmStartMs = 0L
        timingPcmTotalMs = 0L
        timingUtteranceStartMs = 0L
    }

    init {
        // Pre-wire ModelManager but do NOT load the model yet.
        // Loading happens in initStt().
        modelManager = ModelManager(
            modelPath = "",
            sttErrorListener = null,
            readyListener = null,
            whisperModel = whisperModel
        )
        stateMachine.forceSet(SttLifecycleState.INITIALISED)
        SttLogger.lifecycle("SpeechToText constructed — model NOT loaded. Call initStt() to initialise.")
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
     * Idempotent: on second and subsequent calls, returns [SttReturnCode.SUCCESS]
     * immediately without reloading the model, re-running warm-up, or
     * reconstructing STT scaffolding.
     *
     * On first call, performs INITIALISATION:
     * 1. Loads the Whisper model synchronously via [ModelManager.loadModelIfNeeded].
     * 2. Runs mandatory warm-up inference (once per app lifetime).
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
        // ── Idempotency guard: already initialised ─────────────────────────
        if (isInitialised) {
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

        // ── Step 4: Construct STT scaffolding based on mode ───────────────
        val isManualMode = config.startStrategy.type.uppercase() == "MANUAL" &&
            config.stopStrategy.type.uppercase() == "MANUAL"

        if (isManualMode) {
            // Manual mode: MinimalPollingController only.
            minimalProcessorController = MinimalPollingController(
                audioSource = captureManager,
                stopRequestedRef = { this@SpeechToText.stopRequested }
            )
            SttLogger.lifecycle("initStt: Manual mode — minimal scaffolding constructed")
        } else {
            // Auto mode: VAD + accumulator + ProcessorController.
            val vad = Vad(this.config)
            vad.debugLogging = this.config.debugLoggingEnabled
            activeVad = vad

            val accumulator = UtteranceAccumulator(this.config)
            accumulator.sttErrorListener = this@SpeechToText.sttErrorListener
            if (debugOptions.forceTimeout) {
                accumulator.forceTimeout = true
            }
            utteranceAccumulator = accumulator

            val handler = UtteranceHandler()
            utteranceHandler = handler

            accumulator.onSpeechStart = {
                processorController?.resetVadActiveMs()
            }

            val processor = ProcessorController(
                audioSource = captureManager,
                vad = vad,
                utteranceAccumulator = accumulator,
                listener = handler,
                sampleRate = 16000,
                debugLogging = this.config.debugLoggingEnabled,
                stopRequestedRef = { this@SpeechToText.stopRequested }
            )
            processorController = processor

            SttLogger.lifecycle("initStt: Auto mode — full scaffolding constructed")
        }

        // ── Step 5: Mark initialised ──────────────────────────────────────
        isInitialised = true
        SttLogger.lifecycle("initStt: initialisation complete")

        return SessionResult(SttReturnCode.SUCCESS, null)
    }

    /**
     * Start an STT session using the config previously set via [setConfig].
     *
     * Requires that [initStt] has been called first. If not called, returns
     * [SttReturnCode.CONFIG_NOT_SET].
     *
     * Start/stop is driven by the [SttRunConfig] strategies:
     * - [Config.startStrategy] defines when capture begins.
     * - [Config.stopStrategy] defines when capture ends.
     *
     * Manually raised events (via [startSession] and [stopAndTranscribe])
     * are consumed by [ManualStart] and [ManualStop] strategies respectively.
     *
     * ## Return codes
     *
     * | [SttReturnCode.CONFIG_NOT_SET] | [setConfig] was not called, or [initStt] was not called. |
     * | [SttReturnCode.SUCCESS] | Session started successfully. |
     * | [SttReturnCode.ENGINE_ERROR] | Internal pipeline error. |
     */
    fun startSession(): SessionResult {
        val storedConfig = runConfig
        if (storedConfig == null) {
            return SessionResult(SttReturnCode.CONFIG_NOT_SET, null)
        }

        if (!isInitialised) {
            SttLogger.lifecycleW("startSession() called before initStt() — returning CONFIG_NOT_SET")
            return SessionResult(SttReturnCode.CONFIG_NOT_SET, null)
        }

        if (!modelManager.isReady) {
            SttLogger.lifecycleW("startSession() called but model is not ready — returning ENGINE_ERROR")
            return SessionResult(SttReturnCode.ENGINE_ERROR, null)
        }

        synchronized(stateLock) {
            if (stateMachine.currentState is SttLifecycleState.READY ||
                stateMachine.currentState is SttLifecycleState.INITIALISED
            ) {
                events.manualStartPressed.raise()

                if (!config.startStrategy.shouldStart(events, activeVad)) {
                    return SessionResult(SttReturnCode.SUCCESS, null)
                }

                sessionStartMs = System.currentTimeMillis()
                captureManager.beginPcmCapture()

                if (isManualMode()) {
                    captureManager.activatePcmCapture()
                    minimalProcessorController?.start()
                } else {
                    captureManager.beginSttProcessing()
                    startProcessor()
                }
            } else {
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

            if (isManualMode()) {
                minimalProcessorController?.stop()
            } else {
                processorController?.stop()
            }

            val finalPcm = captureManager.finalize()

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

            if (isManualMode()) {
                minimalProcessorController?.stop()
            } else {
                processorController?.stop()
            }

            sessionStartMs = 0L
            captureManager.restartCapture()
            captureManager.reset()
            isRunning.set(false)
            stopRequested = false
            resetTiming()
            if (stateMachine.currentState is SttLifecycleState.RECORDING ||
                stateMachine.currentState is SttLifecycleState.FINALISING ||
                stateMachine.currentState is SttLifecycleState.STOPPED
            ) {
                stateMachine.forceSet(SttLifecycleState.READY)
            }
        }
    }

    // ------- destroy() ------------------------------------------------

    fun destroy() {
        synchronized(stateLock) {
            minimalProcessorController?.stop()
            processorController?.stop()
            captureManager.shutdown()
            isRunning.set(false)
            stopRequested = false
            resetTiming()
            modelManager.unload()
            isInitialised = false
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
                startProcessor()
            }
        }
    }

    /**
     * Start the processor for the current session.
     *
     * Called from [startSession] after the start strategy has approved and
     * PCM capture has begun. Uses the pre-built processor controller created
     * during [initStt].
     *
     * Must be called from within [stateLock].
     */
    private fun startProcessor() {
        if (isRunning.get()) return

        if (!modelManager.isReady) {
            SttLogger.lifecycleW("startProcessor() called before model ready -- ignoring")
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
            SttLogger.lifecycleW("startProcessor() called from ${stateMachine.currentState} -- ignoring")
            return
        }

        resetTiming()
        SttLogger.config("Active config: $config")

        if (!stateMachine.transitionTo(SttLifecycleState.RECORDING)) {
            return
        }

        timingPcmStartMs = System.currentTimeMillis()

        val controller = if (isManualMode()) {
            minimalProcessorController
        } else {
            processorController
        }

        if (controller == null) {
            SttLogger.error("code=INTEGRATION_ERROR, message=\"startProcessor(): controller is null — call initStt() first\"")
            return
        }

        controller.start()
        timingUtteranceStartMs = System.currentTimeMillis()
        isRunning.set(true)
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
     * VAD-related getters (vadActiveMs, vadConfidence, lastUtteranceDurationMs)
     * return null to indicate "not applicable" rather than "silence" or
     * "no speech". Callers should check [supportsVadMetrics] before using
     * VAD data.
     */
    private class MinimalPollingController(
        private val audioSource: AudioSource,
        private val stopRequestedRef: () -> Boolean
    ) : PollingController {

        @Volatile
        private var isRunning: Boolean = false

        /** null — VAD is not used in Manual mode. */
        override val vadActiveMs: Long? = null

        /** null — accumulator is not used in Manual mode. */
        override val lastUtteranceDurationMs: Int? = null

        /** null — VAD is not used in Manual mode. */
        override val vadConfidence: Float? = null

        private var workerThread: Thread? = null

        override fun supportsVadMetrics(): Boolean = false

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
