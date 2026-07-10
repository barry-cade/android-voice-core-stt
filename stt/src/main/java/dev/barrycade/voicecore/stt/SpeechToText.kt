package dev.barrycade.voicecore.stt

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Main entry point for the STT pipeline.
 *
 * Configuration is via [SttRunConfig]:
 * 1. Call [setConfig] with a validated [SttRunConfig].
 * 2. Call [startSession] to begin recording and transcription.
 * 3. Use [stopAndTranscribe] to stop manually (MANUAL_MANUAL strategy).
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
    private val startTrigger: StartTriggerStrategy = ManualStartTrigger(),
    private val stopTrigger: StopTriggerStrategy = ManualStopTrigger(),
    private val captureManager: SessionManager = CaptureManager(),
    private var captureStrategy: CaptureStrategy = ManualManualStrategy(
        ManualManualSpecific(
            energyThreshold = 0.03f,
            maxDurationMs = 30000,
            abnormalSilenceMs = 5000,
            drainMode = DrainMode.DRAIN_FROM_NEXT_FRAME
        )
    )
) {
    companion object {
        /**
         * Create a [SpeechToText] with a minimal default configuration.
         *
         * The caller must call [setConfig] with a [SttRunConfig] before
         * calling [startSession].
         *
         * Model loading and warm-up begin immediately in the constructor.
         * Audio capture also begins immediately (CaptureManager is pre-wired).
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

    // ── Session-scoped state (reset by resetForNextSession) ────────────────
    private var processorController: ProcessorController? = null
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

        // CaptureManager is constructed and starts AudioCapture immediately.
        // Transition to INITIALISED (CaptureManager pre-wired, mic running).
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
                    if (startRequested) {
                        startRequested = false
                        start()
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
     * On success, stores [config] internally, rebuilds the capture strategy
     * from the parsed strategy-specific config, and returns [SessionResult]
     * with [SttReturnCode.SUCCESS].
     * Does NOT start recording. Call [startSession] after this.
     */
    fun setConfig(config: SttRunConfig): SessionResult {
        val validationResult = SttRunConfigValidator.validate(config)
        if (validationResult != null) {
            return validationResult
        }
        runConfig = config

        // Rebuild capture strategy from parsed config so drainMode and other
        // strategy-specific fields take effect immediately.
        val specific = config.strategySpecific
        if (specific is ManualManualSpecific) {
            captureStrategy = ManualManualStrategy(specific)
        }
        // Future strategies (e.g. ManualAuto) would be handled here.

        return SessionResult(SttReturnCode.SUCCESS, null)
    }

    /**
     * Start an STT session using the config previously set via [setConfig].
     *
     * Lifecycle routing is determined by [SttLifeCycleStrategy]:
     * - [MANUAL_MANUAL]: uses [ManualStartTrigger] + [ManualStopTrigger].
     * - [MANUAL_AUTO]: uses [ManualStartTrigger] + [AutoSilenceStopTrigger].
     *
     * CaptureManager is pre-wired (microphone already running). This method:
     * 1. Calls [CaptureManager.begin] to start buffering frames into the session buffer.
     * 2. If the model is ready, starts the processor immediately.
     * 3. If the model is still warming up, queues the start and replays when ready.
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
                // Capture is already running. Begin buffering frames via strategy.
                captureStrategy.onStartPressed(captureManager)

                if (stateMachine.currentState is SttLifecycleState.READY) {
                    // Model is ready, start the processor immediately.
                    start()
                } else {
                    // Model still warming up. Queue start request.
                    // begin() was already called above — frames are buffering.
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
        val finalPcm: FloatArray
        val timingMs: Long

        synchronized(stateLock) {
            SttLogger.pcm("[STOP] entered -- isRunning=${isRunning.get()}, state=${stateMachine.currentState}")

            if (!stopTrigger.shouldStop()) return

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

            // Extract PCM before invoking the stop callback.
            finalPcm = captureManager.finalize()

            // Invoke strategy stop callback (e.g. stop capture).
            captureStrategy.onStopPressed(captureManager)

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

            timingMs = if (timingPcmStartMs > 0) {
                System.currentTimeMillis() - timingPcmStartMs
            } else {
                0L
            }
        }

        // ═════════════════════════════════════════════════════════════════
        // Phase 2: Submit inference (outside stateLock)
        // ═════════════════════════════════════════════════════════════════
        submitInferenceAndDispatch(
            pcm = finalPcm,
            code = SttReturnCode.SUCCESS,
            vadActiveMs = 0L,
            utteranceMs = 0L,
            captureMs = timingMs
        )

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

    internal fun start() {
        synchronized(stateLock) {
            if (isRunning.get()) return

            if (!startTrigger.shouldStart()) return
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

            // CaptureManager is already running. The processor uses
            // CaptureManager.pollFrame() which both buffers into session
            // and returns frames for VAD processing.
            val processor = createProcessor(captureManager)

            processorController = processor
            processor.start()
            timingUtteranceStartMs = System.currentTimeMillis()
            isRunning.set(true)
        }
    }

    private fun createProcessor(capture: AudioSource): ProcessorController {
        val vad = Vad(config)
        vad.debugLogging = config.debugLoggingEnabled

        val accumulator = UtteranceAccumulator(
            config,
            stopTrigger = this@SpeechToText.stopTrigger
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
        processor.onAutoStop = {
            synchronized(stateLock) {
                shutdownPipelineOnAutoStop()
            }
        }
        processor.onAbnormalTermination = { code ->
            synchronized(stateLock) {
                shutdownPipelineOnAbnormal(code)
            }
        }
        return processor
    }

    /**
     * Shutdown pipeline on auto-stop (auto-silence silence trigger).
     * Captures PCM from accumulator and submits inference.
     */
    private fun shutdownPipelineOnAutoStop() {
        val pcm: FloatArray?
        synchronized(stateLock) {
            processorController = null
            isRunning.set(false)
            pcm = null  // Auto-stop already has PCM in processor — finalize via CaptureManager
        }
        // Fallback: use CaptureManager finalize for any remaining PCM.
        val remainingPcm = captureManager.finalize()
        if (remainingPcm.isNotEmpty()) {
            submitInferenceAndDispatch(remainingPcm, SttReturnCode.SUCCESS, 0L, 0L, 0L)
        }
    }

    /**
     * Shutdown pipeline on abnormal termination.
     * Dispatches the error code without inference.
     */
    private fun shutdownPipelineOnAbnormal(code: SttReturnCode) {
        synchronized(stateLock) {
            processorController = null
            isRunning.set(false)
        }
        // PCM from accumulator was already dispatched via UtteranceHandler.
        // No additional action needed.
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
        val effectiveSilenceMs = when (stopTrigger) {
            is AutoSilenceStopTrigger -> config.manualAutoAutoSilenceMs.toLong()
            else -> config.manualManualAbnormalSilenceMs.toLong()
        }

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
