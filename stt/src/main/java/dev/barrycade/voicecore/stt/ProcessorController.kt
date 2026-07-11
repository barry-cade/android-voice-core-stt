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
     * timeout (maxUtteranceLengthMs exceeded) or abnormal silence.
     * The callee should clean up audio capture and lifecycle state.
     * The [code] parameter contains the [SttReturnCode] categorising the outcome.
     */
    @Volatile
    internal var onAbnormalTermination: ((code: SttReturnCode) -> Unit)? = null

    /**
     * Optional callback invoked when the processor stops due to an automatic
     * stop trigger (e.g. auto-silence). The callee should clean up audio
     * capture and lifecycle state.
     */
    @Volatile
    internal var onAutoStop: (() -> Unit)? = null

    /** Accumulated VAD active time in milliseconds. */
    @Volatile
    var vadActiveMs: Long = 0L
        private set

    /** Last utterance duration in milliseconds, captured at finalization. */
    @Volatile
    var lastUtteranceDurationMs: Int = 0
        private set

    /** RMS sampler for diagnostic logging. */
    internal val rmsSampler: RmsSampler = RmsSampler(
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

        val runnable = Runnable {
            runProcessingLoop()
        }

        val thread = Thread(runnable, "ProcessorControllerThread")
        workerThread = thread
        thread.start()
    }

    /**
     * Core processing loop, executing on the worker thread.
     * Polls frames, runs VAD, accumulates utterances, and delivers
     * finalized PCM via [UtteranceListener].
     *
     * The [FrameResult] from processChunk drives the loop:
     * - Continue: keep processing
     * - UtteranceReady: deliver PCM to listener for transcription, keep processing
     *   (StopStrategy decides capture boundary)
     */
    private fun runProcessingLoop() {
        while (isRunning.get()) {
            try {
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

                val result = utteranceAccumulator.processChunk(frame, isSpeechFrame)

                when (result) {
                    is FrameResult.Continue -> {
                        // Keep processing
                    }

                    is FrameResult.UtteranceReady -> {
                        lastUtteranceDurationMs = utteranceAccumulator.lastUtteranceDurationMs
                        SttLogger.pcmD("Utterance ready with ${result.pcm.size} samples")
                        listener.onUtteranceReady(result.pcm, SttReturnCode.SUCCESS)
                        // Do NOT stop the loop — StopStrategy decides capture boundary.
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (t: Throwable) {
                SttLogger.error("code=INTERNAL_EXCEPTION, message=\"${t.message}\"")
                SttLogger.error("code=INTERNAL_EXCEPTION, trace=${t.stackTraceToString()}")
                isRunning.set(false)
                onAbnormalTermination?.invoke(SttReturnCode.ENGINE_ERROR)
                break
            }
        }
    }

    /**
     * Stop the processor worker thread.
     *
     * If called from the processor's own worker thread (e.g. via [onAutoStop]
     * or [onAbnormalTermination] callback), the self-join ([Thread.join]) is
     * a no-op because a thread cannot join itself. No behavioural issue arises.
     *
     * Idempotent: multiple calls are safe after the thread has stopped.
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) return
        workerThread?.join(500)
        workerThread = null
        rmsSampler.reset()
    }

    /**
     * Drain remaining frames from [audioSource] into the accumulator
     * after the processing loop has stopped. Returns the finalized PCM
     * if one is produced during drain, or null if no frames were drained.
     *
     * Called by SpeechToText on the STOP path after the processor loop exits.
     */
    fun drainRemainingFrames(): FloatArray? {
        var drainFinalized: FloatArray? = null
        var drainedCount = 0

        while (true) {
            val frame = audioSource.pollFrame()
            if (frame == null) break
            val isSpeech = vad.isSpeech(frame)
            val result = utteranceAccumulator.processChunk(frame, isSpeech)
            if (result is FrameResult.UtteranceReady) {
                drainFinalized = result.pcm
            }
            drainedCount++
        }
        SttLogger.pcm("[STOP] drained $drainedCount frames into accumulator")
        return drainFinalized
    }

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
