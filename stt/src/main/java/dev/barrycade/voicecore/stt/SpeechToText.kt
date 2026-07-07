package dev.barrycade.voicecore.stt

import java.util.concurrent.atomic.AtomicBoolean

class SpeechToText internal constructor(
    private val config: RuntimeSttConfig,
    modelPath: String,
    private val whisperModel: WhisperModel = WhisperBridge
) {
    companion object {
        fun create(config: SttConfig): SpeechToText {
            return SpeechToText(
                RuntimeSttConfig(
                    energyThreshold = config.energyThreshold,
                    silencePaddingMs = config.silencePaddingMs,
                    preRollMs = config.preRollMs,
                    maxUtteranceLengthMs = config.maxUtteranceLengthMs,
                    stableChunkSizeMs = config.stableChunkSizeMs,
                    motionMode = MotionModeConfig(
                        energyThreshold = config.motionModeEnergyThreshold,
                        silencePaddingMs = config.motionModeSilencePaddingMs
                    ),
                    debugLoggingEnabled = config.debugLoggingEnabled
                ),
                config.modelPath
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
    private var externalReadyListener: SttReadyListener? = null

    private val internalReadyListener: SttReadyListener = object : SttReadyListener {
        override fun onSttReady() {
            SttLogger.pcm("[READY_CALLBACK] internalReadyListener fired — startRequested=$startRequested")
            synchronized(stateLock) {
                transitionTo(SttLifecycleState.READY)
            }
            externalReadyListener?.onSttReady()
            if (startRequested) {
                startRequested = false
                SttLogger.pcm("[READY_CALLBACK] calling start() from callback")
                this@SpeechToText.start()
            } else {
                SttLogger.pcm("[READY_CALLBACK] no queued start request — waiting for UI")
            }
        }
    }

    private val modelManager = ModelManager(modelPath, null, internalReadyListener, whisperModel)

    private var audioSource: AudioSource? = null
    private var processorController: ProcessorController? = null
    @Volatile private var stopRequested: Boolean = false
    private var lastTranscribedText: String? = null
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

    // ────────────────────────────────────────────────────────────────────────
    // Public API setters
    // ────────────────────────────────────────────────────────────────────────

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

    fun setReadyListener(listener: SttReadyListener) {
        externalReadyListener = listener
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

    fun dumpConfig() {
        SttLogger.config("Active config: $config")
    }

    // ────────────────────────────────────────────────────────────────────────
    // start()
    // ────────────────────────────────────────────────────────────────────────

    fun start() {
        synchronized(stateLock) {
            SttLogger.pcm("[START] entered — isRunning=${isRunning.get()}, " +
                "state=${currentState.javaClass.simpleName}, " +
                "isReady=${modelManager.isReady}")
            if (isRunning.get()) return

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
            dumpConfig()

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
            processor.onTimeoutStop = createTimeoutStopCallback()

            processorController = processor
            processor.start()
            timingUtteranceStartMs = System.currentTimeMillis()
            isRunning.set(true)
            SttLogger.pcm("[START] capture running — isRunning=true")

            if (hadQueuedStop) {
                SttLogger.pcm("[START] stop was queued — triggering stop now")
                stopAndTranscribe()
            }
        }
    }

    /**
     * Check if the pipeline is ready to start, or queue the request if the
     * model is still warming up. Returns true if we should continue, false
     * if the request was queued or rejected.
     */
    private fun isReadyOrCanQueue(): Boolean {
        if (currentState is SttLifecycleState.UNINITIALISED || !modelManager.isReady) {
            SttLogger.lifecycleW("start() called early — queued until READY")
            startEarlyCaptureForWarmup()
            startRequested = true
            return false
        }
        if (currentState !is SttLifecycleState.READY) {
            SttLogger.lifecycleW("start() called while in ${currentState.javaClass.simpleName} — ignoring")
            return false
        }
        return true
    }

    /**
     * Start AudioCapture early so audio is buffered during model warm-up.
     */
    private fun startEarlyCaptureForWarmup() {
        if (audioSource != null) return
        SttLogger.pcm("[START] starting AudioCapture early for warm-up buffering")
        val earlyCapture = CaptureController()
        if (earlyCapture.startCapture()) {
            audioSource = earlyCapture
            SttLogger.pcm("[START] AudioCapture buffering during warm-up")
        } else {
            SttLogger.pcmE("[START] Early AudioCapture failed — no buffering during warm-up")
        }
    }

    /**
     * Ensure a capture controller is started and return it.
     * Reuses an existing one if started during warm-up.
     * Returns null on failure.
     */
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

    /**
     * Create a ProcessorController wired to [capture] with a named listener.
     */
    private fun createProcessor(capture: AudioSource): ProcessorController {
        val vad = Vad(config)
        vad.debugLogging = config.debugLoggingEnabled

        val accumulator = UtteranceAccumulator(config)
        accumulator.sttErrorListener = this@SpeechToText.sttErrorListener
        if (debugOptions.forceTimeout) {
            accumulator.forceTimeout = true
        }
        accumulator.onSpeechStart = {
            processorController?.resetVadActiveMs()
        }

        val utteranceHandler = UtteranceHandler()

        return ProcessorController(
            audioSource = capture,
            vad = vad,
            utteranceAccumulator = accumulator,
            listener = utteranceHandler,
            sampleRate = 16000,
            debugLogging = config.debugLoggingEnabled,
            stopRequestedRef = { this@SpeechToText.stopRequested }
        )
    }

    /**
     * Returns the timeout cleanup callback. Delegates to [handleTimeoutStop].
     */
    private fun createTimeoutStopCallback(): () -> Unit {
        return ::handleTimeoutStop
    }

    private fun handleTimeoutStop() {
        synchronized(stateLock) {
            SttLogger.pcm("[TIMEOUT] cleaning up pipeline")
            audioSource?.stopCapture()
            audioSource = null
            isRunning.set(false)
            if (currentState is SttLifecycleState.RECORDING) {
                transitionTo(SttLifecycleState.FINALISING)
            }
            transitionTo(SttLifecycleState.READY)
            stopRequested = false
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // stopAndTranscribe()
    // ────────────────────────────────────────────────────────────────────────

    fun stopAndTranscribe() {
        synchronized(stateLock) {
            SttLogger.pcm("[STOP] entered — isRunning=${isRunning.get()}")

            if (!isRunning.get()) {
                SttLogger.pcm("[STOP] queued — recording not started yet")
                stopRequested = true
                return
            }

            isRunning.set(false)

            try {
                if (!transitionTo(SttLifecycleState.FINALISING)) return

                processorController?.stop()
                stopRequested = true

                val finalizedPcm = processorController?.drainRemainingFrames()

                val procVadMs = processorController?.vadActiveMs ?: 0L
                val procUtterMs = (processorController?.lastUtteranceDurationMs ?: 0).toLong()
                val pcm = finalizedPcm ?: processorController?.stopAndFinalize()
                SttLogger.pcm("[STOP] stopAndFinalize returned pcm=${pcm != null}")

                processorController = null
                audioSource?.stopCapture()
                audioSource = null

                val capMs = if (timingPcmStartMs > 0) {
                    System.currentTimeMillis() - timingPcmStartMs
                } else {
                    0L
                }

                if (pcm != null) {
                    runInferenceAndDispatch(pcm, procVadMs, procUtterMs, capMs)
                } else {
                    SttLogger.pcmW("no pcm available from accumulator")
                }

                transitionTo(SttLifecycleState.READY)
                stopRequested = false
            } catch (t: Throwable) {
                dispatchError(t)
            }
        }
    }

    fun stop() = stopAndTranscribe()

    // ────────────────────────────────────────────────────────────────────────
    // Shared inference + dispatch
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Single shared method for transcribing PCM and dispatching the result.
     * Used by both VAD-triggered (UtteranceHandler) and STOP-triggered paths.
     */
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

        val snapshot = SttTimingSnapshot(
            vadActiveMs = vadActiveMs,
            utteranceDurationMs = utteranceMs,
            silencePaddingMs = config.silencePaddingMs.toLong(),
            preRollMs = config.preRollMs.toLong(),
            inferenceMs = whisperMs,
            totalPipelineMs = totalMs
        )

        lastTranscribedText = text
        onTimingListener?.invoke(captureMs, vadActiveMs, whisperMs, totalMs)
        dispatchResult(text, snapshot)
    }

    // ────────────────────────────────────────────────────────────────────────
    // destroy()
    // ────────────────────────────────────────────────────────────────────────

    fun destroy() {
        synchronized(stateLock) {
            processorController?.stop()
            processorController = null
            audioSource?.stopCapture()
            audioSource = null
            modelManager.unload()
            // Hard reset — bypasses transitionTo validation since this is
            // a full teardown, not a lifecycle step.
            currentState = SttLifecycleState.UNINITIALISED
        }
        modelManager.shutdown()
    }

    // ────────────────────────────────────────────────────────────────────────
    // Lifecycle helpers
    // ────────────────────────────────────────────────────────────────────────

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
        SttLogger.lifecycleE("illegal transition: ${from.javaClass.simpleName} → ${newState.javaClass.simpleName}")
        return false
    }

    private fun dispatchResult(text: String, timing: SttTimingSnapshot?) {
        lastTranscribedText = text
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

    // ────────────────────────────────────────────────────────────────────────
    // Named inner listener — replaces the anonymous object in start()
    // ────────────────────────────────────────────────────────────────────────

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
