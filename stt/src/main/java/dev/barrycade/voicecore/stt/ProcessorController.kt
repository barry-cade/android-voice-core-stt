package dev.barrycade.voicecore.stt

import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ProcessorController owns VAD, utterance accumulation, and STOP finalisation.
 * It polls PCM frames from [CaptureController], runs them through VAD and
 * [UtteranceAccumulator], and delivers finalized utterances via [UtteranceListener].
 *
 * ## Thread ownership
 *
 * | Thread | Owns | Notes |
 * |--------|------|-------|
 * | Worker thread (T3) | Processing loop: [runProcessingLoop] | Created in [start], joined in [stop] |
 * | Caller thread (SpeechToText) | [start], [stop], [drainRemainingFrames], [stopAndFinalize] | Serialized via [SpeechToText.stateLock] |
 *
 * ## Self-join safety
 *
 * [stop] guards against self-join by checking `thread !== Thread.currentThread()`
 * before calling join(). If the caller is the worker thread itself (e.g. called
 * from a callback chain within the processing loop), the reference is cleared
 * without joining.
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
    private val stopRequestedRef: () -> Boolean,
    private val sttErrorListener: SttErrorListener? = null,
    /** Optional audio pre-processor for noise resilience (HPF, ZCR). */
    private val preProcessor: AudioPreProcessor? = null
) : PollingController {
    private val isRunning = AtomicBoolean(false)

    /** Worker thread reference. Guarded by [workerLock] for write, [@Volatile] for read. */
    @Volatile
    private var workerThread: Thread? = null

    /** Lock for [workerThread] read-and-clear sequences. */
    private val workerLock = Any()

    /** Accumulated VAD active time in milliseconds. */
    @Volatile
    override var vadActiveMs: Long? = 0L
        private set

    /** Last utterance duration in milliseconds, captured at finalization. */
    @Volatile
    override var lastUtteranceDurationMs: Int? = 0
        private set

    /** RMS sampler for diagnostic logging. */
    override val rmsSampler: RmsSampler = RmsSampler(
        sampleRate = sampleRate,
        debugLogging = debugLogging,
        onSample = { avg, peak, floor ->
            SttLogger.pcmD("[RMS] avg=$avg peak=$peak floor=$floor")
        }
    )

    /** Pass-through VAD confidence for diagnostic use. */
    @Volatile
    override var vadConfidence: Float? = 0f
        private set

    override fun supportsVadMetrics(): Boolean = true

    /**
     * Start the processor worker thread. It polls frames from CaptureController,
     * runs VAD, accumulates utterances, and delivers finalized PCM via listener.
     *
     * Must be called from the SpeechToText caller thread.
     */
    override fun start() {
        if (isRunning.getAndSet(true)) return

        val runnable = Runnable {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            runProcessingLoop()
        }

        val thread = Thread(runnable, "ProcessorControllerThread")
        synchronized(workerLock) {
            workerThread = thread
        }
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

                var frame = audioSource.pollFrame()
                if (frame == null) {
                    val deadline = System.nanoTime() + 250_000_000L
                    while (frame == null && System.nanoTime() < deadline) {
                        Thread.sleep(1)
                        frame = audioSource.pollFrame()
                    }
                    if (frame == null) continue
                }

                if (!isRunning.get()) break

                if (debugLogging) {
                    SttLogger.pcmD("dequeue frame for VAD, size=${frame.size}")
                }

                // Run noise resilience pre-processing (HPF, ZCR) before VAD.
                val isNoise = preProcessor?.process(frame) ?: false

                val isSpeechFrame = if (isNoise) false else vad.isSpeech(frame)
                val confidence = vad.vadConfidence
                vadConfidence = confidence
                if (debugLogging) {
                    SttLogger.vadD("confidence=$confidence")
                }

                rmsSampler.feedFrame(frame)

                if (isSpeechFrame) {
                    SttLogger.vadD("speechFrame: rmsAboveThreshold=true, lastEnergy=${vad.lastFrameEnergy}")
                    val frameDurationMs = (frame.size * 1000L) / 16000L
                    vadActiveMs = (vadActiveMs ?: 0L) + frameDurationMs
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
                sttErrorListener?.onSttError(SttError(
                    code = SttErrorCode.INTERNAL_EXCEPTION,
                    message = "Processing loop failed: ${t.message}",
                    cause = t,
                    details = listOf(
                        "vadActiveMs=$vadActiveMs",
                        "lastUtteranceMs=$lastUtteranceDurationMs"
                    )
                ))
                isRunning.set(false)
                break
            }
        }
    }

    /**
     * Stop the processor worker thread.
     *
     * Must be called from the SpeechToText caller thread.
     *
     * ## Self-join safety
     *
     * If the calling thread IS the worker thread (the processing loop
     * itself), the reference is cleared without joining. This prevents a
     * thread from joining itself, which would hang forever.
     *
     * Idempotent: multiple calls are safe after the thread has stopped.
     */
    override fun stop() {
        if (!isRunning.getAndSet(false)) return
        val threadToJoin: Thread?
        synchronized(workerLock) {
            threadToJoin = workerThread
            workerThread = null
        }
        if (threadToJoin != null && threadToJoin !== Thread.currentThread()) {
            threadToJoin.join(500)
        }
        rmsSampler.reset()
    }

    /**
     * Drain remaining frames from [audioSource] into the accumulator
     * after the processing loop has stopped. Returns the finalized PCM
     * if one is produced during drain, or null if no frames were drained.
     *
     * Called by SpeechToText on the STOP path after the processor loop exits.
     */
    override fun drainRemainingFrames(): FloatArray? {
        var drainFinalized: FloatArray? = null
        var drainedCount = 0

        while (true) {
            val frame = audioSource.pollFrame()
            if (frame == null) break
            val isNoise = preProcessor?.process(frame) ?: false
            val isSpeech = if (isNoise) false else vad.isSpeech(frame)
            val result = utteranceAccumulator.processChunk(frame, isSpeech)
            if (result is FrameResult.UtteranceReady) {
                drainFinalized = result.pcm
            }
            drainedCount++
        }
        SttLogger.pcm("drained $drainedCount frames into accumulator")
        return drainFinalized
    }

    /**
     * Finalise the current utterance and return the PCM buffer.
     * Called from the deterministic Stop path, after stopRequested has been set.
     * Returns null if no PCM was accumulated.
     */
    override fun stopAndFinalize(): FloatArray? {
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
    override fun resetVadActiveMs() {
        vadActiveMs = 0L
    }
}
