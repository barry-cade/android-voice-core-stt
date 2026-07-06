package dev.barrycade.voicecore.stt

import java.util.concurrent.atomic.AtomicBoolean

class SpeechToText internal constructor(
    private val config: RuntimeSttConfig,
    modelPath: String,
    private val whisperModel: WhisperModel = WhisperBridge
) {
    companion object {
        fun create(energyThreshold: Float, silencePaddingMs: Int, preRollMs: Int,
            maxUtteranceLengthMs: Int, stableChunkSizeMs: Int,
            motionModeEnergyThreshold: Float, motionModeSilencePaddingMs: Int,
            modelPath: String): SpeechToText {
            return SpeechToText(RuntimeSttConfig(energyThreshold = energyThreshold,
                silencePaddingMs = silencePaddingMs, preRollMs = preRollMs,
                maxUtteranceLengthMs = maxUtteranceLengthMs,
                stableChunkSizeMs = stableChunkSizeMs,
                motionMode = MotionModeConfig(energyThreshold = motionModeEnergyThreshold,
                    silencePaddingMs = motionModeSilencePaddingMs)), modelPath)
        }
    }

    internal var forceAudioInitFailure: Boolean = false
    internal var forceTimeout: Boolean = false

    private var sttErrorListener: SttErrorListener? = null
    private var onResult: ((String) -> Unit)? = null
    private var onResultWithTiming: ((text: String, timing: SttTimingSnapshot?) -> Unit)? = null
    private var onError: ((Throwable) -> Unit)? = null
    internal var onTimingCallback: ((SttTiming) -> Unit)? = null
    var onTimingListener: ((pcmMs: Long, vadActiveMs: Long, whisperMs: Long, totalMs: Long) -> Unit)? = null

    private val isRunning = AtomicBoolean(false)
    private val isInferencing = AtomicBoolean(false)
    private val stateLock = Any()
    private val lifecycleManager = SttLifecycleManager()
    @Volatile private var startRequested = false
    private var externalReadyListener: SttReadyListener? = null

    private val internalReadyListener = SttReadyListener {
        SttLogger.pcm("[READY_CALLBACK] internalReadyListener fired — startRequested=$startRequested")
        // Transition UNINITIALISED → READY now that warm-up is complete
        synchronized(stateLock) {
            transitionTo(SttLifecycleState.READY)
        }
        // Notify external listener
        externalReadyListener?.onSttReady()
        // Auto-start if start() was called before READY
        if (startRequested) {
            startRequested = false
            SttLogger.pcm("[READY_CALLBACK] calling start() from callback")
            this.start()
        } else {
            SttLogger.pcm("[READY_CALLBACK] no queued start request — waiting for UI")
        }
    }

    private val modelManager = ModelManager(modelPath, null, internalReadyListener, whisperModel)

    private var captureController: CaptureController? = null
    private var processorController: ProcessorController? = null
    @Volatile private var stopRequested: Boolean = false
    private var lastTranscribedText: String? = null
    private var timingPcmStartMs: Long = 0L
    private var timingPcmTotalMs: Long = 0L
    private var timingUtteranceStartMs: Long = 0L
    private fun resetTiming() { timingPcmStartMs = 0L; timingPcmTotalMs = 0L; timingUtteranceStartMs = 0L }

    init { config.validate(); modelManager.initAsync() }

    fun setOnResultListener(l: (String) -> Unit) { onResult = l }
    fun setOnResultWithTimingListener(l: (text: String, timing: SttTimingSnapshot?) -> Unit) { onResultWithTiming = l }
    fun setOnErrorListener(l: (Throwable) -> Unit) { onError = l }
    fun setSttErrorListener(l: SttErrorListener) { sttErrorListener = l }

    fun setReadyListener(listener: SttReadyListener) {
        externalReadyListener = listener
    }

    fun setDebugOptions(forceAudioInitFailure: Boolean = false,
        forceWhisperLoadFailure: Boolean = false, forceTimeout: Boolean = false) {
        this.forceAudioInitFailure = forceAudioInitFailure
        this.forceTimeout = forceTimeout
        modelManager.forceWhisperLoadFailure = forceWhisperLoadFailure
    }

    fun start() {
        synchronized(stateLock) {
            SttLogger.pcm("[START] entered — isRunning=${isRunning.get()}, state=${lifecycleManager.currentState.javaClass.simpleName}, isReady=${modelManager.isReady}")
            if (isRunning.get()) return

            // ── READY gate: queue if model not yet ready ────────────────
            val currentState = lifecycleManager.currentState
            if (currentState is SttLifecycleState.UNINITIALISED || !modelManager.isReady) {
                SttLogger.lifecycleW("start() called early — queued until READY")
                // Start AudioCapture NOW so audio is buffered during warm-up
                if (captureController == null) {
                    SttLogger.pcm("[START] starting AudioCapture early for warm-up buffering")
                    val earlyCapture = CaptureController()
                    if (earlyCapture.startCapture()) {
                        captureController = earlyCapture
                        SttLogger.pcm("[START] AudioCapture buffering during warm-up")
                    } else {
                        SttLogger.pcmE("[START] Early AudioCapture failed — no buffering during warm-up")
                    }
                }
                startRequested = true
                return
            }

            if (currentState !is SttLifecycleState.READY) {
                SttLogger.lifecycleW("start() called while in ${currentState.javaClass.simpleName} — ignoring")
                return
            }

            if (modelManager.initFailed) { dispatchError(RuntimeException("Model initialisation failed")); return }

            SttLogger.pcm("[START] beginning capture setup — stopRequested=$stopRequested, startRequested=$startRequested")
            resetTiming()
            dumpConfig()
            if (forceAudioInitFailure) { dispatchError(RuntimeException("Forced test: AudioCapture init")); return }

            // ── Use existing capture controller if started early, or create new ──
            val capture: CaptureController
            val existingCapture = captureController
            if (existingCapture != null) {
                capture = existingCapture
            } else {
                val newCapture = CaptureController()
                if (!newCapture.startCapture()) {
                    dispatchError(RuntimeException("Audio capture failed"))
                    return
                }
                captureController = newCapture
                capture = newCapture
            }
            if (!transitionTo(SttLifecycleState.RECORDING)) { capture.stopCapture(); return }
            timingPcmStartMs = System.currentTimeMillis()

            val processor = ProcessorController(capture,
                vad = Vad(config).apply { debugLogging = config.debugLoggingEnabled },
                utteranceAccumulator = UtteranceAccumulator(config).apply {
                    sttErrorListener = this@SpeechToText.sttErrorListener
                    if (this@SpeechToText.forceTimeout) this.forceTimeout = true
                    onSpeechStart = { processorController?.resetVadActiveMs() }
                },
                listener = object : UtteranceListener {
                    override fun onUtteranceReady(pcm: FloatArray) {
                        if (!isRunning.get()) return
                        if (!isInferencing.compareAndSet(false, true)) return
                        try {
                            val infStartMs = System.currentTimeMillis()
                            val samples = pcm.toShortArray()
                            val text = try { modelManager.transcribe(samples).trim() }
                            catch (t: Throwable) { SttLogger.whisperE("inference failed: ${t.message}"); "" }
                            if (text.isNotBlank()) {
                                val whisperMs = System.currentTimeMillis() - infStartMs
                                val vadMs = processorController?.vadActiveMs ?: 0L
                                val utterMs = (processorController?.lastUtteranceDurationMs ?: 0).toLong()
                                val totalMs = System.currentTimeMillis() - timingUtteranceStartMs
                                val ts = SttTiming(vadMs.toInt(), utterMs.toInt(), whisperMs.toInt(), totalMs.toInt())
                                lastTranscribedText = text
                                onTimingCallback?.invoke(ts)
                                onTimingListener?.invoke(timingPcmTotalMs, vadMs, whisperMs, totalMs)
                                dispatchResult(text, null)
                            }
                        } finally { isInferencing.set(false) }
                    }
                }, sampleRate = 16000, debugLogging = config.debugLoggingEnabled,
                stopRequestedRef = { this@SpeechToText.stopRequested })
            // ── Clear stopRequested before starting processor — allows buffered frames to be processed ──
            val hadQueuedStop = stopRequested
            stopRequested = false

            processor.start(); processorController = processor
            timingUtteranceStartMs = System.currentTimeMillis(); isRunning.set(true)
            SttLogger.pcm("[START] capture running — isRunning=true")

            // ── Consume queued STOP — processor processes buffered frames, then we finalize ──
            if (hadQueuedStop) {
                SttLogger.pcm("[START] stop was queued — triggering stop now")
                stopAndTranscribe()
            }
        }
    }

    fun stopAndTranscribe() {
        synchronized(stateLock) {
            SttLogger.pcm("[STOP] entered — isRunning=${isRunning.get()}")
            // ── Queue STOP when not recording — executes once recording begins ──
            if (!isRunning.get()) {
                SttLogger.pcm("[STOP] queued — recording not started yet")
                stopRequested = true
                return
            }
            isRunning.set(false)
            try {
                if (!transitionTo(SttLifecycleState.FINALISING)) return

                // ── Stop processor thread first — no more frame competition ──
                processorController?.stop()
                stopRequested = true

                // ── Drain remaining frames from capture queue into accumulator ──
                var drainedCount = 0
                var drainFinalized: FloatArray? = null
                val capture = captureController
                val proc = processorController
                if (capture != null && proc != null) {
                    val accumulator = proc.getAccumulator()
                    val procVad = proc.getVad()
                    while (true) {
                        val frame = capture.pollFrame()
                        if (frame == null) break
                        val isSpeech = procVad.isSpeech(frame)
                        val result = accumulator.processChunk(frame, isSpeech)
                        if (result != null) {
                            drainFinalized = result
                        }
                        drainedCount++
                    }
                }
                SttLogger.pcm("[STOP] drained $drainedCount frames into accumulator")

                val procVadMs = processorController?.vadActiveMs ?: 0L
                val procUtterMs = (processorController?.lastUtteranceDurationMs ?: 0).toLong()
                val pcm = drainFinalized ?: processorController?.stopAndFinalize()
                SttLogger.pcm("[STOP] stopAndFinalize returned pcm=${pcm != null}")
                processorController = null
                captureController?.stopCapture(); captureController = null
                val capMs = if (timingPcmStartMs > 0) System.currentTimeMillis() - timingPcmStartMs else 0L

                if (pcm != null) {
                    val infStartMs = System.currentTimeMillis()
                    val text = modelManager.transcribe(pcm.toShortArray()).trim()
                    val whisperMs = System.currentTimeMillis() - infStartMs
                    if (text.isNotBlank()) {
                        val totalMs = System.currentTimeMillis() - timingUtteranceStartMs
                        val ts = SttTiming(procVadMs.toInt(), procUtterMs.toInt(), whisperMs.toInt(), totalMs.toInt())
                        lastTranscribedText = text
                        onTimingCallback?.invoke(ts)
                        onTimingListener?.invoke(capMs, procVadMs, whisperMs, totalMs)
                        dispatchResult(text, null)
                    }
                } else SttLogger.pcmW("no pcm available from accumulator")
                transitionTo(SttLifecycleState.READY)
            } catch (t: Throwable) { dispatchError(t) }
        }
    }

    fun stop() = stopAndTranscribe()

    fun destroy() {
        synchronized(stateLock) {
            processorController?.stop(); processorController = null
            captureController?.stopCapture(); captureController = null
            modelManager.unload()
            lifecycleManager.currentState = SttLifecycleState.UNINITIALISED
        }
        modelManager.shutdown()
    }

    fun dumpConfig() { SttLogger.config("Active config: $config") }

    private fun transitionTo(newState: SttLifecycleState): Boolean {
        val from = lifecycleManager.currentState
        if (from == newState) return true
        val valid = when (from) {
            is SttLifecycleState.UNINITIALISED -> newState is SttLifecycleState.READY
            is SttLifecycleState.READY -> newState is SttLifecycleState.RECORDING
            is SttLifecycleState.RECORDING -> newState is SttLifecycleState.FINALISING
            is SttLifecycleState.FINALISING -> newState is SttLifecycleState.READY
        }
        if (valid) { lifecycleManager.currentState = newState; return true }
        SttLogger.lifecycleE("illegal transition: ${from.javaClass.simpleName} → ${newState.javaClass.simpleName}")
        return false
    }

    private fun dispatchResult(text: String, timing: SttTimingSnapshot?) {
        lastTranscribedText = text; onResultWithTiming?.invoke(text, timing); onResult?.invoke(text)
    }

    private fun dispatchError(t: Throwable) {
        onError?.invoke(t)
        sttErrorListener?.onSttError(SttError(SttErrorCategory.UNKNOWN, SttErrorCode.INTERNAL_EXCEPTION,
            t.message ?: "Unknown error", cause = t))
    }

    private fun FloatArray.toShortArray(): ShortArray {
        val shorts = ShortArray(size)
        for (i in indices) shorts[i] = (kotlin.math.max(-1f, kotlin.math.min(1f, this[i])) * Short.MAX_VALUE).toInt().toShort()
        return shorts
    }
}
