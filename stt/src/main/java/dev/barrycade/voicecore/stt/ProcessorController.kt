package dev.barrycade.voicecore.stt

import java.util.concurrent.atomic.AtomicBoolean

/**
 * ProcessorController owns VAD, utterance accumulation, and STOP finalisation.
 * It polls PCM frames from [CaptureController], runs them through VAD and
 * [UtteranceAccumulator], and delivers finalized utterances via [UtteranceListener].
 *
 * Forbidden: model load, warm-up, AudioCapture start/stop, Whisper inference.
 *
 * @param stopRequestedRef Supplier that returns true when Stop has been requested.
 *        When true, the processing loop stops polling new frames and exits.
 */
internal class ProcessorController(
    private val audioSource: AudioSource,
    private val vad: Vad,
    private val utteranceAccumulator: UtteranceAccumulator,
    private val listener: UtteranceListener,
    private val sampleRate: Int = 16000,
    private val debugLogging: Boolean = false,
    private val stopRequestedRef: () -> Boolean
) {
    private val isRunning = AtomicBoolean(false)
    private var workerThread: Thread? = null

    /**
     * Optional callback invoked when the processor stops due to a terminal
     * timeout (maxUtteranceLengthMs exceeded). The callee should clean up
     * audio capture and lifecycle state.
     */
    @Volatile
    internal var onTimeoutStop: (() -> Unit)? = null

    /** Accumulated VAD active time in milliseconds. */
    @Volatile
    var vadActiveMs: Long = 0L
        private set

    /** Last utterance duration in milliseconds, captured at finalization. */
    @Volatile
    var lastUtteranceDurationMs: Int = 0
        private set

    /** RMS sampler for diagnostic logging. */
    val rmsSampler: RmsSampler = RmsSampler(
        sampleRate = sampleRate,
        debugLogging = debugLogging,
        onSample = { avg, peak, floor ->
            SttLogger.pcmD("[RMS] avg=$avg peak=$peak floor=$floor")
        }
    )

    /** Pass-through VAD confidence for diagnostic use. */
    @Volatile
    var vadConfidence: Float = 0f
        private set

    /**
     * Start the processor worker thread. It polls frames from CaptureController,
     * runs VAD, accumulates utterances, and delivers finalized PCM via listener.
     */
    fun start() {
        if (isRunning.getAndSet(true)) return

        workerThread = Thread({
            while (isRunning.get()) {
                try {
                    // Single guard at loop top: stop requested means skip processing
                    if (stopRequestedRef()) {
                        Thread.sleep(10L)
                        continue
                    }

                    val frame = audioSource.pollFrame()
                    if (frame == null) {
                        Thread.sleep(10L)
                        continue
                    }

                    if (!isRunning.get()) break

                    SttLogger.pcmD("dequeue frame for VAD, size=${frame.size}")

                    val isSpeechFrame = vad.isSpeech(frame)
                    val confidence = vad.vadConfidence
                    vadConfidence = confidence
                    if (debugLogging) {
                        SttLogger.vadD("confidence=$confidence")
                    }

                    rmsSampler.feedFrame(frame)

                    if (isSpeechFrame) {
                        SttLogger.vadD("speechFrame: rmsAboveThreshold=true, lastEnergy=${vad.lastFrameEnergy}")
                        val frameDurationMs = (frame.size * 1000L) / 16000L
                        vadActiveMs += frameDurationMs
                    }

                    val utterance = utteranceAccumulator.processChunk(frame, isSpeechFrame)
                    if (utterance != null) {
                        lastUtteranceDurationMs = utteranceAccumulator.lastUtteranceDurationMs
                        SttLogger.pcmD("Utterance finalized with ${utterance.size} samples")
                        listener.onUtteranceReady(utterance)

                        // ── Terminal timeout: stop the processor loop ────────
                        if (utteranceAccumulator.timeoutFired) {
                            SttLogger.pcm("[TIMEOUT] max utterance reached — stopping processor")
                            utteranceAccumulator.timeoutFired = false
                            isRunning.set(false)
                            onTimeoutStop?.invoke()
                            break
                        }
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (t: Throwable) {
                    SttLogger.error("code=INTERNAL_EXCEPTION, message=\"${t.message}\"")
                }
            }
        }, "ProcessorControllerThread").apply { start() }
    }

    /**
     * Stop the processor worker thread.
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) return
        workerThread?.join(500)
        workerThread = null
        rmsSampler.reset()
    }

    /**
     * Expose VAD for external frame-level speech detection during STOP drain.
     */
    fun getVad(): Vad = vad

    /**
     * Expose accumulator for external frame draining during STOP.
     */
    fun getAccumulator(): UtteranceAccumulator = utteranceAccumulator

    /**
     * Finalise the current utterance and return the PCM buffer.
     * Called from the deterministic Stop path, after stopRequested has been set.
     * Returns null if no PCM was accumulated.
     */
    fun stopAndFinalize(): FloatArray? {
        val pcm = utteranceAccumulator.finaliseUtterance()
        if (pcm != null) {
            lastUtteranceDurationMs = utteranceAccumulator.lastUtteranceDurationMs
        }
        return pcm
    }

    /**
     * Reset per-utterance VAD active time to 0.
     * Called when UtteranceAccumulator detects a PRE_ROLL → SPEECH transition.
     */
    fun resetVadActiveMs() {
        vadActiveMs = 0L
    }
}
