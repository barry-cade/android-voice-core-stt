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
    private val stopTrigger: StopTriggerStrategy = ManualStopTrigger()
) {
    companion object {
        /**
         * Create a [SpeechToText] with a minimal default configuration.
         *
         * The caller must call [setConfig] with a [SttRunConfig] before
         * calling [startSession].
         *
         * Model loading and warm-up begin immediately in the constructor.
         * [startSession] will wait for model readiness before starting
         * audio capture.
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
    private var audioSource: AudioSource? = null
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
                if (stateMachine.currentState is SttLifecycleState.UNINITIALISED) {
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
     * On success, stores [config] internally and returns [SessionResult] with [SttReturnCode.SUCCESS].
     * Does NOT start recording. Call [startSession] after this.
     */
    fun setConfig(config: SttRunConfig): SessionResult {
        val validationResult = SttRunConfigValidator.validate(config)
        if (validationResult != null) {
            return validationResult
        }
        runConfig = config
        return SessionResult(SttReturnCode.SUCCESS, null)
    }

    /**
     * Start an STT session using the config previously set via [setConfig].
     *
     * Lifecycle routing is determined by [SttLifeCycleStrategy]:
     * - [MANUAL_MANUAL]: uses [ManualStartTrigger] + [ManualStopTrigger].
     * - [MANUAL_AUTO]: uses [ManualStartTrigger] + [AutoSilenceStopTrigger].
     *
     * If the model is not yet ready (warm-up in progress), the start request
     * is queued and replayed automatically when warm-up completes. The caller
     * does not need to call [startSession] again.
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

        // Start audio capture BEFORE model warm-up (if warm-up is still
        // running), so any speech uttered during the warm-up window
        // accumulates in the frame queue.
        synchronized(stateLock) {
            if (stateMachine.currentState is SttLifecycleState.READY) {
                // Model is ready, start immediately.
                start()
            } else if (stateMachine.currentState is SttLifecycleState.UNINITIALISED) {
                // Model still warming up. Queue start request and begin
                // capture so speech during warm-up is not lost.
                startRequested = true
                startCaptureImmediate()
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
     * Acceptable states for stop:
     * - [SttLifecycleState.READY]: user pressed STOP during warm-up or pre-speech;
     *   no audio capture or accumulator exists yet, so just transition to STOPPED.
     * - [SttLifecycleState.RECORDING]: normal stop during active capture;
     *   drain accumulator, run inference, deliver result.
     * - [SttLifecycleState.FINALISING]: already stopping, allow re-entry.
     *
     * States UNINITIALISED and STOPPED are ignored.
     */
    fun stopAndTranscribe() {
        // ── Phase 1: Lock-protected cleanup ──────────────────────────────
        // Only state transitions and PCM drain happen under the lock.
        // Inference dispatch happens outside the lock in Phase 2.
        val extractedPcm: FloatArray?

        synchronized(stateLock) {
            SttLogger.pcm("[STOP] entered -- isRunning=${isRunning.get()}, state=${stateMachine.currentState}")
            if (!stopTrigger.shouldStop()) return

            if (stateMachine.currentState is SttLifecycleState.STOPPED) {
                SttLogger.pcm("[STOP] ignoring -- state=STOPPED")
                return
            }

            // UNINITIALISED and READY: audio capture may already be running
            // (pre-started before warm-up). Drain queued frames and transcribe.
            if (stateMachine.currentState is SttLifecycleState.UNINITIALISED ||
                stateMachine.currentState is SttLifecycleState.READY
            ) {
                val activeCapture = audioSource
                if (activeCapture != null) {
                    SttLogger.pcm("[STOP] stopping during warm-up with active capture")
                    stopRequested = true
                    isRunning.set(false)
                    extractedPcm = drainQueuedFrames(activeCapture)
                } else {
                    SttLogger.pcm("[STOP] stopping during READY (no audio capture)")
                    isRunning.set(false)
                    setStoppedDirect()
                    return
                }
                if (extractedPcm != null) {
                    shutdownPipeline(extractedPcm)
                } else {
                    SttLogger.pcm("[STOP] no PCM from queued frames")
                    setStoppedDirect()
                }
                return
            }

            // ── RECORDING or FINALISING: finalise PCM and run inference ──
            isRunning.set(false)

            extractedPcm = try {
                if (!stateMachine.transitionTo(SttLifecycleState.FINALISING)) {
                    return
                }

                // Signal the processor loop that stop is requested.
                // Must be set BEFORE processorController?.stop() so that
                // drainRemainingFrames sees the flag.
                stopRequested = true

                // Stop the processor worker thread.
                processorController?.stop()

                // Drain any remaining frames from the audio source queue,
                // then fall back to force-finalising the accumulator.
                processorController?.drainRemainingFrames()
                    ?: processorController?.stopAndFinalize()
            } catch (t: Throwable) {
                dispatchError(t)
                return
            }
        }
        // ═══════════════════════════════════════════════════════════════
        // Phase 2: Inference dispatch (outside stateLock)
        // ═══════════════════════════════════════════════════════════════
        if (extractedPcm != null) {
            shutdownPipeline(extractedPcm)
        } else {
            synchronized(stateLock) {
                SttLogger.pcmW("no pcm available from accumulator")
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
     * Safe to call multiple times. Idempotent when no session is active.
     */
    fun resetForNextSession() {
        synchronized(stateLock) {
            SttLogger.lifecycle("resetForNextSession: state=${stateMachine.currentState}")
            processorController?.stop()
            processorController = null
            audioSource?.stopCapture()
            audioSource = null
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
            // If UNINITIALISED or READY, leave as-is.
        }
    }

    // ------- destroy() ------------------------------------------------

    fun destroy() {
        synchronized(stateLock) {
            processorController?.stop()
            processorController = null
            audioSource?.stopCapture()
            audioSource = null
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
     * Start audio capture immediately, before model warm-up.
     *
     * The capture fills [AudioCapture.frameQueue] with PCM frames during
     * the ~4 second warm-up window. When [start] is called later, the
     * [ProcessorController] drains these frames through VAD + accumulator.
     *
     * Safe to call multiple times — [ensureCaptureStarted] is idempotent.
     */
    internal fun startCaptureImmediate() {
        synchronized(stateLock) {
            ensureCaptureStarted()
        }
    }

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
            if (stateMachine.currentState !is SttLifecycleState.READY) {
                SttLogger.lifecycleW("start() called from ${stateMachine.currentState} -- ignoring")
                return
            }

            resetTiming()
            SttLogger.config("Active config: $config")

            val capture = ensureCaptureStarted()
            if (capture == null) return

            if (!stateMachine.transitionTo(SttLifecycleState.RECORDING)) {
                capture.stopCapture()
                return
            }

            timingPcmStartMs = System.currentTimeMillis()

            // Clear any frames accumulated during warm-up (ambient noise,
            // not intentional speech). The processor should only see live
            // capture frames from here onwards.
            capture.clearQueue()

            val processor = createProcessor(capture)

            processorController = processor
            processor.start()
            timingUtteranceStartMs = System.currentTimeMillis()
            isRunning.set(true)
        }
    }

    private fun ensureCaptureStarted(): AudioSource? {
        val existingCapture = audioSource
        if (existingCapture != null) {
            return existingCapture
        }
        val newCapture = CaptureController()
        if (!newCapture.startCapture()) {
            dispatchError(RuntimeException("Audio capture failed"))
            return null
        }
        audioSource = newCapture
        return newCapture
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
                shutdownPipeline()
            }
        }
        processor.onAbnormalTermination = { code ->
            synchronized(stateLock) {
                shutdownPipeline()
            }
        }
        return processor
    }

    /**
     * Drain queued frames from [AudioSource] and finalise into a single PCM buffer.
     * Used when stopping before the processor loop has started (e.g. STOP during warm-up).
     * Creates a temporary VAD and accumulator to process the queued frames.
     */
    private fun drainQueuedFrames(source: AudioSource): FloatArray? {
        val tempVad = Vad(config)
        val tempAccumulator = UtteranceAccumulator(config, stopTrigger = stopTrigger)
        while (true) {
            val frame = source.pollFrame()
            if (frame == null) break
            val isSpeech = tempVad.isSpeech(frame)
            val result = tempAccumulator.processChunk(frame, isSpeech)
            when (result) {
                is FrameResult.NormalFinalize -> return result.pcm
                is FrameResult.AutoStop -> return result.pcm
                is FrameResult.AbnormalTerminateWithPcm -> return result.pcm
                else -> { }
            }
        }
        return tempAccumulator.finaliseUtterance()
    }

    /**
     * Set state to STOPPED directly, bypassing the transition validator.
     * Used when stopping from UNINITIALISED or READY (early stop during warm-up)
     * where normal lifecycle transitions are not applicable.
     */
    private fun setStoppedDirect() {
        stateMachine.forceSet(SttLifecycleState.STOPPED)
    }

    private fun shutdownPipeline(pcm: FloatArray) {
        processorController?.stop()
        processorController = null
        audioSource?.stopCapture()
        audioSource = null
        isRunning.set(false)

        if (stateMachine.currentState is SttLifecycleState.RECORDING) {
            stateMachine.transitionTo(SttLifecycleState.FINALISING)
        }
        if (stateMachine.currentState is SttLifecycleState.FINALISING ||
            stateMachine.currentState is SttLifecycleState.READY
        ) {
            stateMachine.transitionTo(SttLifecycleState.STOPPED)
        }
        if (stateMachine.currentState is SttLifecycleState.UNINITIALISED) {
            // Direct assignment: capture was started before lifecycle transition.
            // We bypass the state machine here because we never entered RECORDING.
            stateMachine.forceSet(SttLifecycleState.STOPPED)
        }

        if (pcm.isNotEmpty()) {
            val vadMs = 0L
            val utterMs = 0L
            val capMs = if (timingPcmStartMs > 0) {
                System.currentTimeMillis() - timingPcmStartMs
            } else {
                0L
            }
            submitInferenceAndDispatch(pcm, SttReturnCode.SUCCESS, vadMs, utterMs, capMs)
        }
    }

    private fun shutdownPipeline() {
        processorController?.stop()
        processorController = null
        audioSource?.stopCapture()
        audioSource = null
        isRunning.set(false)

        if (stateMachine.currentState is SttLifecycleState.RECORDING) {
            stateMachine.transitionTo(SttLifecycleState.FINALISING)
        }
        if (stateMachine.currentState is SttLifecycleState.FINALISING ||
            stateMachine.currentState is SttLifecycleState.READY
        ) {
            stateMachine.transitionTo(SttLifecycleState.STOPPED)
        }
        if (stateMachine.currentState is SttLifecycleState.UNINITIALISED) {
            stateMachine.forceSet(SttLifecycleState.STOPPED)
        }
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
