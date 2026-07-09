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

    @Volatile private var startRequested = false

    private val internalReadyListener: SttReadyListener = object : SttReadyListener {
        override fun onSttReady() {
            SttLogger.pcm("[READY_CALLBACK] internalReadyListener fired -- startRequested=$startRequested")
            synchronized(stateLock) {
                transitionTo(SttLifecycleState.READY)
            }
            if (startRequested) {
                startRequested = false
                SttLogger.pcm("[READY_CALLBACK] calling start() from callback")
                this@SpeechToText.start()
            } else {
                SttLogger.pcm("[READY_CALLBACK] no queued start request -- waiting for UI")
            }
        }
    }

    private val modelManager = ModelManager(modelPath, null, internalReadyListener, whisperModel)

    /** [SttRunConfig] set via [setConfig]. */
    private var runConfig: SttRunConfig? = null

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

    init {
        config.validate()
        modelManager.initAsync()
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

        stt.setOnResultListener { text ->
            onResult?.invoke(text)
        }

        stt.start()

        return SessionResult(SttReturnCode.SUCCESS, null)
    }

    /**
     * Stop the current session and transcribe accumulated audio.
     */
    fun stopAndTranscribe() {
        synchronized(stateLock) {
            SttLogger.pcm("[STOP] entered -- isRunning=${isRunning.get()}")
            if (!stopTrigger.shouldStop()) return

            if (!isRunning.get()) {
                SttLogger.pcm("[STOP] queued -- recording not started yet")
                stopRequested = true
                return
            }

            isRunning.set(false)

            try {
                if (!transitionTo(SttLifecycleState.FINALISING)) return

                processorController?.stop()
                stopRequested = true

                val pcm = processorController?.drainRemainingFrames()
                    ?: processorController?.stopAndFinalize()

                if (pcm != null) {
                    shutdownPipeline(pcm, SttReturnCode.OK)
                } else {
                    SttLogger.pcmW("no pcm available from accumulator")
                    transitionTo(SttLifecycleState.READY)
                    stopRequested = false
                }
            } catch (t: Throwable) {
                dispatchError(t)
            }
        }
    }

    fun stop() = stopAndTranscribe()

    // ------- destroy() ------------------------------------------------

    fun destroy() {
        synchronized(stateLock) {
            processorController?.stop()
            processorController = null
            audioSource?.stopCapture()
            audioSource = null
            modelManager.unload()
            currentState = SttLifecycleState.UNINITIALISED
        }
        modelManager.shutdown()
    }

    // ======== Internal pipeline ========================================

    internal fun start() {
        synchronized(stateLock) {
            if (isRunning.get()) return

            if (!startTrigger.shouldStart()) return
            if (!isReadyOrCanQueue()) return
            if (modelManager.initFailed) {
                dispatchError(RuntimeException("Model initialisation failed"))
                return
            }
            if (debugOptions.forceAudioInitFailure) {
                dispatchError(RuntimeException("Forced test: AudioCapture init"))
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
            val hadQueuedStop = stopRequested
            stopRequested = false

            val processor = createProcessor(capture)

            processorController = processor
            processor.start()
            timingUtteranceStartMs = System.currentTimeMillis()
            isRunning.set(true)

            if (hadQueuedStop) {
                stopAndTranscribe()
            }
        }
    }

    private fun isReadyOrCanQueue(): Boolean {
        if (currentState is SttLifecycleState.UNINITIALISED || !modelManager.isReady) {
            SttLogger.lifecycleW("start() called early -- queued until READY")
            startEarlyCaptureForWarmup()
            startRequested = true
            return false
        }
        if (currentState !is SttLifecycleState.READY) {
            SttLogger.lifecycleW("start() called while in ${currentState.javaClass.simpleName} -- ignoring")
            return false
        }
        return true
    }

    private fun startEarlyCaptureForWarmup() {
        if (audioSource != null) return
        val earlyCapture = CaptureController()
        if (earlyCapture.startCapture()) {
            audioSource = earlyCapture
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
                shutdownPipeline(SttReturnCode.OK)
            }
        }
        processor.onAbnormalTermination = { code ->
            synchronized(stateLock) {
                shutdownPipeline(code)
            }
        }
        return processor
    }

    private fun shutdownPipeline(pcm: FloatArray, code: SttReturnCode) {
        processorController?.stop()
        processorController = null
        audioSource?.stopCapture()
        audioSource = null
        isRunning.set(false)
        stopRequested = false

        if (currentState is SttLifecycleState.RECORDING) {
            transitionTo(SttLifecycleState.FINALISING)
        }
        transitionTo(SttLifecycleState.READY)

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

    private fun shutdownPipeline(code: SttReturnCode) {
        processorController?.stop()
        processorController = null
        audioSource?.stopCapture()
        audioSource = null
        isRunning.set(false)
        stopRequested = false

        if (currentState is SttLifecycleState.RECORDING) {
            transitionTo(SttLifecycleState.FINALISING)
        }
        transitionTo(SttLifecycleState.READY)
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

    private fun transitionTo(newState: SttLifecycleState): Boolean {
        val from = currentState
        if (from == newState) return true
        val valid = when (from) {
            is SttLifecycleState.UNINITIALISED -> newState is SttLifecycleState.READY
            is SttLifecycleState.READY -> newState is SttLifecycleState.RECORDING
            is SttLifecycleState.RECORDING -> newState is SttLifecycleState.FINALISING
            is SttLifecycleState.FINALISING -> newState is SttLifecycleState.READY
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
