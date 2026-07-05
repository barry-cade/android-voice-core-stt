package dev.barrycade.voicecore.stt

import java.util.concurrent.atomic.AtomicBoolean

/**
 * SttProcessor polls FloatArray frames from AudioCapture and routes them to the VAD and
 * utterance accumulator. It does not call Whisper; it only emits finalized utterances.
 *
 * @param stopRequestedRef Supplier that returns true when Stop has been requested.
 *        When true, PCM ingestion, VAD processing, and accumulator updates are frozen.
 *        Defaults to { false } for backward compatibility in tests.
 */
internal class SttProcessor(
    private val audioCapture: AudioCapture,
    private val vad: Vad,
    private val utteranceAccumulator: UtteranceAccumulator,
    private val listener: UtteranceListener,
    private val calibrationLogger: VadCalibrationLogger? = null,
    private val debugLogging: Boolean = false,
    private val sampleRate: Int = 16000,
    private val stopRequestedRef: () -> Boolean = { false }
) {
    private val isRunning = AtomicBoolean(false)
    private var workerThread: Thread? = null

    /** Accumulated VAD active time in milliseconds. */
    @Volatile
    internal var vadActiveMs: Long = 0L
        private set

    /** Last utterance duration in milliseconds, captured at finalization. */
    @Volatile
    internal var lastUtteranceDurationMs: Int = 0
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
    internal var vadConfidence: Float = 0f
        private set

    /**
     * Reset per-utterance VAD active time to 0.
     * Called when UtteranceAccumulator detects a SILENCE → SPEECH transition,
     * and by SpeechToText.stopAndTranscribe() for manual invocation.
     * Guarantees per-utterance timing, not cumulative.
     */
    internal fun resetVadActiveMs() {
        vadActiveMs = 0L
    }

    fun start() {
        if (isRunning.getAndSet(true)) return

        workerThread = Thread({
            while (isRunning.get()) {
                try {
                    val frame = audioCapture.frameQueue.poll()
                    if (frame == null) {
                        Thread.sleep(10L)
                        continue
                    }

                                                            // Drop frames if stop was requested while frame was in transit
                    if (!isRunning.get()) break

                    // ── Section 3: Freeze PCM ingestion when stopRequested ──
                    if (stopRequestedRef()) {
                        SttLogger.pcm("[PCM] dropped frame due to stopRequested=true")
                        continue
                    }

                    SttLogger.pcmD("dequeue frame for VAD, size=${frame.size}")

                    val isSpeechFrame = vad.isSpeech(frame)
                    val confidence = vad.vadConfidence
                    vadConfidence = confidence
                    if (debugLogging) {
                        SttLogger.vadD("confidence=$confidence")
                    }

                    // ── Section 4: Freeze VAD processing when stopRequested ──
                    if (stopRequestedRef()) {
                        SttLogger.vad("[VAD] skipped frame due to stopRequested=true")
                        continue
                    }

                    // ── RMS sampling (every ~200ms) ─────────────────────
                    rmsSampler.feedFrame(frame)

                    val rms = computeRms(frame)
                    calibrationLogger?.logFrame(frame, isSpeechFrame, rms, 0)

                    // ── Timing: accumulate VAD active duration ────────────
                    if (isSpeechFrame) {
                        SttLogger.vadD("speechFrame: rmsAboveThreshold=true, lastEnergy=${vad.lastFrameEnergy}")
                        val frameDurationMs = (frame.size * 1000L) / 16000L
                        vadActiveMs += frameDurationMs
                    }

                    // ── Section 5: Freeze accumulator updates when stopRequested ──
                    if (stopRequestedRef()) {
                        SttLogger.pcm("[ACC] skipped update due to stopRequested=true")
                        continue
                    }

                    val utterance = utteranceAccumulator.processChunk(frame, isSpeechFrame)
                    if (utterance != null) {
                        lastUtteranceDurationMs = utteranceAccumulator.lastUtteranceDurationMs
                        SttLogger.pcmD("Utterance finalized with ${utterance.size} samples")
                        calibrationLogger?.logUtteranceFinalized(utterance.size, utterance.size * 1000 / 16000)
                        listener.onUtteranceReady(utterance)
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                                } catch (t: Throwable) {
                    SttLogger.error("code=INTERNAL_EXCEPTION, message=\"${t.message}\"")
                    val error = SttError(
                        category = SttErrorCategory.UNKNOWN,
                        code = SttErrorCode.INTERNAL_EXCEPTION,
                        message = "SttProcessor worker thread error: ${t.message}",
                        lastVadState = vad.lastFrameEnergy > 0f,
                        lastRms = vad.lastFrameEnergy,
                        cause = t,
                        context = mapOf("exception" to t::class.java.simpleName, "detail" to (t.message ?: ""))
                    )
                    sttErrorListener?.onSttError(error)
                }
            }
        }, "SttProcessorThread").apply { start() }
    }

            fun stop() {
        if (!isRunning.getAndSet(false)) return
        workerThread?.join(500)
        workerThread = null
        rmsSampler.reset()
    }

            fun forceFinalize(): FloatArray? {
        val utterance = utteranceAccumulator.forceFinalize()
        if (utterance != null) {
            lastUtteranceDurationMs = utteranceAccumulator.lastUtteranceDurationMs
            calibrationLogger?.logUtteranceFinalized(utterance.size, utterance.size * 1000 / 16000)
        }
        return utterance
    }

    /**
     * finaliseUtterance finalises the current utterance and returns the PCM buffer.
     * Called only from the deterministic Stop path, after stopRequested has been set.
     * Delegates to UtteranceAccumulator.finaliseUtterance().
     */
    fun finaliseUtterance(): FloatArray? {
        val pcm = utteranceAccumulator.finaliseUtterance()
        if (pcm != null) {
            val pcmSize = pcm.size
            lastUtteranceDurationMs = utteranceAccumulator.lastUtteranceDurationMs
            calibrationLogger?.logUtteranceFinalized(pcmSize, pcmSize * 1000 / 16000)
        }
        return pcm
    }

    private fun computeRms(frame: FloatArray): Double {
        if (frame.isEmpty()) return 0.0
        var sumSquares = 0.0
        for (sample in frame) {
            sumSquares += sample * sample
        }
        return kotlin.math.sqrt(sumSquares / frame.size)
    }

    companion object {
        var sttErrorListener: SttErrorListener? = null
    }
}
