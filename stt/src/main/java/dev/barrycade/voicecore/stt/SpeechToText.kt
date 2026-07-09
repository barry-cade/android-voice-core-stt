package dev.barrycade.voicecore.stt

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Main entry point for the STT pipeline.
 *
 * Configuration is via [SttRunConfig]:
 * 1. Call [setConfig] with a validated [SttRunConfig].
 * 2. Call [startSession] to begin recording and transcription.
 * 3. Use [stopAndTranscribe] to stop manually (MANUAL_MANUAL strategy).
 * 4. Call [destroy] to release all resources.
 *
 * Only the new [SttRunConfig] API path is supported.
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
    private var onResultWithTiming: ((text: String, timing: SttTimingSnapshot?) -> Unit)? = null
    private var onError: ((Throwable) -> Unit)? = null
    var onTimingListener: ((pcmMs: Long, vadActiveMs: Long, whisperMs: Long, totalMs: Long) -> Unit)? = null

    private val isRunning = AtomicBoolean(false)
    private val isInferencing = AtomicBoolean(false)
    private val stateLock = Any()

    @Volatile
    private var currentState: SttLifecycleState = SttLifecycleState.UNINITIALISED

    private val modelManager: ModelManager

    /** [SttRunConfig] set via [setConfig]. */
    private var runConfig: SttRunConfig? = null

    private var audioSource: AudioSource? = null
    private var processorController: ProcessorController? = null
    @Volatile private var stopRequested: Boolean = false
    private var timingPcmStartMs: Long = 0L
    private var timingPcmTotalMs: Long = 0L
    private var timingUtteranceStartMs: Long = 0L

    /**
     * Reference to the inner SpeechToText instance created by [startSessionInternal].
     * All lifecycle operations (start, stop, destroy) must be routed through this
     * instance, not the outer shell.
     */
    @Volatile
    private var activeSession: SpeechToText? = null

    private fun resetTiming() {
        timingPcmStartMs = 0L
        timingPcmTotalMs = 0L
        timingUtteranceStartMs = 0L
    }

    init {
        config.validate()
        modelManager = ModelManager(
            modelPath = modelPath,
            sttErrorListener = null,
            readyListener = null,
            whisperModel = whisperModel
        )
        // No auto-init here. initAsync is called explicitly by startSessionInternal
        // with a callback that chains into start().
    }

    // ------- Public API ------------------------------------------------

    fun setOnResultListener(l: (String) -> Unit) {
        onResult = l
    }

    fun setOnResultWithTimingListener(l: (text: String, timing: SttTimingSnapshot?) -> Unit) {
        onResultWithTiming = l
    }

    fun setOnErrorListener(l: (Throwable) -> Unit) {
        onError = l
    }

    fun setSttErrorListener(l: SttErrorListener) {
        sttErrorListener = l
    }

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
     * ## Return codes
     *
     * | [SttReturnCode.CONFIG_NOT_SET] | [setConfig] was not called. |
     * | [SttReturnCode.SUCCESS] | Session started successfully. |
     * | [SttReturnCode.ENGINE_ERROR] | Internal pipeline error. |
     */
    fun startSession(): SessionResult {
        val storedConfig = runConfig
        if (storedConfig == null) {
            return SessionResult(SttReturnCode.CONFIG_NOT_SET, null)
        }

        val runtimeConfig = RuntimeSttConfig.fromSttRunConfig(storedConfig)
        val engine = storedConfig.ttsEngineConfig

        return startSessionInternal(
            runCfg = storedConfig,
            runtimeConfig = runtimeConfig,
            modelPath = engine.modelPath
        )
    }

    /**
     * Internal: create a new STT instance for the session and start it.
     *
     * After model warm-up completes (via [ModelManager.initAsync] callback),
     * [start] is called immediately — no queued-start flags, no ready listener branching.
     */
    internal fun startSessionInternal(
        runCfg: SttRunConfig,
        runtimeConfig: RuntimeSttConfig,
        modelPath: String
    ): SessionResult {
        val stt = SpeechToText(
            config = runtimeConfig,
            modelPath = modelPath,
            whisperModel = WhisperBridge,
            startTrigger = ManualStartTrigger(),
            stopTrigger = ManualStopTrigger()
        )

        // Forward all result listeners from the inner instance to the outer shell.
        stt.setOnResultListener { text ->
            onResult?.invoke(text)
        }
        stt.setOnResultWithTimingListener { text, timing ->
            onResultWithTiming?.invoke(text, timing)
        }
        stt.setOnErrorListener { t ->
            onError?.invoke(t)
        }
        stt.sttErrorListener = sttErrorListener

        // Store reference so stopAndTranscribe() routes to the active session.
        activeSession = stt

        // Start the pipeline immediately when model warm-up completes.
        // Transition to READY first, then start.
        stt.modelManager.initAsync {
            stt.transitionTo(SttLifecycleState.READY)
            stt.start()
        }

        return SessionResult(SttReturnCode.SUCCESS, null)
    }

    /**
     * Stop the current session and transcribe accumulated audio.
     *
     * Routes to the active inner session instance created by [startSessionInternal].
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
        // Route to the active inner session if it exists.
        val session = activeSession
        if (session != null && session !== this) {
            session.stopAndTranscribe()
            return
        }

        synchronized(stateLock) {
            SttLogger.pcm("[STOP] entered -- isRunning=${isRunning.get()}, state=${currentState}")
            if (!stopTrigger.shouldStop()) return

            if (currentState is SttLifecycleState.UNINITIALISED ||
                currentState is SttLifecycleState.STOPPED
            ) {
                SttLogger.pcm("[STOP] ignoring -- state=${currentState}")
                return
            }

            // READY state: no audio capture running, just transition to STOPPED.
            // No PCM to finalise — user stopped before any audio was captured.
            if (currentState is SttLifecycleState.READY) {
                SttLogger.pcm("[STOP] stopping during READY (no audio capture)")
                isRunning.set(false)
                transitionTo(SttLifecycleState.STOPPED)
                return
            }

            // ── RECORDING or FINALISING: finalise PCM and run inference ──
            isRunning.set(false)

            try {
                if (!transitionTo(SttLifecycleState.FINALISING)) return

                // Signal the processor loop that stop is requested.
                // Must be set BEFORE processorController?.stop() so that
                // drainRemainingFrames sees the flag.
                stopRequested = true

                // Stop the processor worker thread.
                processorController?.stop()

                // Drain any remaining frames from the audio source queue,
                // then fall back to force-finalising the accumulator.
                val pcm = processorController?.drainRemainingFrames()
                    ?: processorController?.stopAndFinalize()

                if (pcm != null) {
                    shutdownPipeline(pcm)
                } else {
                    SttLogger.pcmW("no pcm available from accumulator")
                    transitionTo(SttLifecycleState.STOPPED)
                }
            } catch (t: Throwable) {
                dispatchError(t)
            }
        }
    }

    fun stop() = stopAndTranscribe()

    // ------- destroy() ------------------------------------------------

    fun destroy() {
        val session = activeSession
        if (session != null && session !== this) {
            session.destroy()
            activeSession = null
            return
        }
        synchronized(stateLock) {
            processorController?.stop()
            processorController = null
            audioSource?.stopCapture()
            audioSource = null
            modelManager.unload()
            if (currentState is SttLifecycleState.RECORDING ||
                currentState is SttLifecycleState.FINALISING
            ) {
                transitionTo(SttLifecycleState.STOPPED)
            }
            currentState = SttLifecycleState.UNINITIALISED
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
            if (currentState !is SttLifecycleState.READY) {
                SttLogger.lifecycleW("start() called from ${currentState} -- ignoring")
                return
            }

            resetTiming()
            SttLogger.config("Active config: $config")

            val capture = ensureCaptureStarted()
            if (capture == null) return

            if (!transitionTo(SttLifecycleState.RECORDING)) {
                capture.stopCapture()
                return
            }

            timingPcmStartMs = System.currentTimeMillis()

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

    private fun shutdownPipeline(pcm: FloatArray) {
        processorController?.stop()
        processorController = null
        audioSource?.stopCapture()
        audioSource = null
        isRunning.set(false)

        if (currentState is SttLifecycleState.RECORDING) {
            transitionTo(SttLifecycleState.FINALISING)
        }
        transitionTo(SttLifecycleState.STOPPED)

        if (pcm.isNotEmpty()) {
            val vadMs = 0L
            val utterMs = 0L
            val capMs = if (timingPcmStartMs > 0) {
                System.currentTimeMillis() - timingPcmStartMs
            } else {
                0L
            }
            runInferenceAndDispatch(pcm, vadMs, utterMs, capMs)
        }
    }

    private fun shutdownPipeline() {
        processorController?.stop()
        processorController = null
        audioSource?.stopCapture()
        audioSource = null
        isRunning.set(false)

        if (currentState is SttLifecycleState.RECORDING) {
            transitionTo(SttLifecycleState.FINALISING)
        }
        transitionTo(SttLifecycleState.STOPPED)
    }

    private fun runInferenceAndDispatch(
        pcm: FloatArray,
        vadActiveMs: Long,
        utteranceMs: Long,
        captureMs: Long
    ) {
        val infStartMs = System.currentTimeMillis()
        val text: String

        try {
            text = modelManager.transcribe(pcm.toShortArray()).trim()
        } catch (t: Throwable) {
            SttLogger.whisperE("inference failed: ${t.message}")
            return
        }

        if (text.isBlank()) {
            return
        }

        val whisperMs = System.currentTimeMillis() - infStartMs
        val totalMs = System.currentTimeMillis() - timingUtteranceStartMs

        val effectiveSilenceMs = when (stopTrigger) {
            is AutoSilenceStopTrigger -> config.manualAutoAutoSilenceMs
            else -> config.manualManualAbnormalSilenceMs
        }
        val snapshot = SttTimingSnapshot(
            vadActiveMs = vadActiveMs,
            utteranceDurationMs = utteranceMs,
            silencePaddingMs = effectiveSilenceMs.toLong(),
            preRollMs = config.preRollMs.toLong(),
            inferenceMs = whisperMs,
            totalPipelineMs = totalMs
        )

        onTimingListener?.invoke(captureMs, vadActiveMs, whisperMs, totalMs)
        dispatchResult(text, snapshot)
    }

    internal fun transitionTo(newState: SttLifecycleState): Boolean {
        val from = currentState
        if (from == newState) return true
        val valid = when (from) {
            is SttLifecycleState.UNINITIALISED -> newState is SttLifecycleState.READY
            is SttLifecycleState.READY -> newState is SttLifecycleState.RECORDING ||
                    newState is SttLifecycleState.STOPPED
            is SttLifecycleState.RECORDING -> newState is SttLifecycleState.FINALISING
            is SttLifecycleState.FINALISING -> newState is SttLifecycleState.STOPPED
            else -> false
        }
        if (valid) {
            currentState = newState
            return true
        }
        SttLogger.lifecycleE("illegal transition: ${from.javaClass.simpleName} -> ${newState.javaClass.simpleName}")
        return false
    }

    private fun dispatchResult(text: String, timing: SttTimingSnapshot?) {
        onResultWithTiming?.invoke(text, timing)
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
        override fun onUtteranceReady(pcm: FloatArray) {
            if (!isRunning.get()) return
            if (!isInferencing.compareAndSet(false, true)) return

            try {
                val vadMs = processorController?.vadActiveMs ?: 0L
                val utterMs = (processorController?.lastUtteranceDurationMs ?: 0).toLong()
                runInferenceAndDispatch(pcm, vadMs, utterMs, timingPcmTotalMs)
            } finally {
                isInferencing.set(false)
            }
        }
    }
}
