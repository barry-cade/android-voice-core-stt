package dev.barrycade.voicecore.stt

/**
 * Owns the Auto-mode STT processing pipeline: VAD, utterance accumulation,
 * and the [ProcessorController] polling loop.
 *
 * ## Thread ownership
 *
 * | Thread | Owns | Notes |
 * |--------|------|-------|
 * | SpeechToText caller thread | [start], [stop], [drainRemainingFrames], [stopAndFinalize] | Serialized via [SpeechToText.stateLock] |
 * | ProcessorController worker thread (T3) | VAD, utterance accumulation, PCM polling | Created by [ProcessorController.start] |
 * | UtteranceListener callback | [ProcessingListener.onUtteranceReady] | Delivered on T3 — callers must post to their own thread |
 *
 * All public methods delegate to [ProcessorController] which owns its own
 * worker thread. No additional threading is introduced by this controller.
 *
 * Responsibilities:
 * - Construct and own VAD, [UtteranceAccumulator], and [ProcessorController].
 * - Convert internal [UtteranceListener] events into the stable
 *   [ProcessingListener] callback surface.
 * - Expose VAD metrics ([vadActiveMs], [vadConfidence], [lastUtteranceDurationMs])
 *   and the [RmsSampler] for diagnostic use by [SpeechToText].
 *
 * No lifecycle transitions, no session-epoch ownership, no mode branching.
 * Processing lifecycle (start/stop) is delegated to [ProcessorController].
 */
internal class SttProcessingController(
    config: RuntimeSttConfig,
    captureManager: SessionManager,
    stopRequestedRef: () -> Boolean,
    sttErrorListener: SttErrorListener?,
    forceTimeout: Boolean = false,
    listener: ProcessingListener
) {

    /** VAD instance used by the processing pipeline. */
    val vad: Vad

    /** Utterance accumulator used by the processing pipeline. */
    val utteranceAccumulator: UtteranceAccumulator

    /** The underlying processor controller that owns the polling loop. */
    val processorController: ProcessorController

    /** Accumulated VAD active time in milliseconds from the processor. */
    val vadActiveMs: Long?
        get() = processorController.vadActiveMs

    /** VAD confidence for diagnostic use from the processor. */
    val vadConfidence: Float?
        get() = processorController.vadConfidence

    /** Last utterance duration in milliseconds from the processor. */
    val lastUtteranceDurationMs: Int?
        get() = processorController.lastUtteranceDurationMs

    /** RMS sampler for diagnostic logging from the processor. */
    val rmsSampler: RmsSampler
        get() = processorController.rmsSampler

    init {
        vad = Vad(config)
        vad.debugLogging = config.debugLoggingEnabled

        utteranceAccumulator = UtteranceAccumulator(config)
        utteranceAccumulator.sttErrorListener = sttErrorListener
        if (forceTimeout) {
            utteranceAccumulator.forceTimeout = true
        }

        utteranceAccumulator.onSpeechStart = {
            processorController.resetVadActiveMs()
        }

        processorController = ProcessorController(
            audioSource = captureManager,
            vad = vad,
            utteranceAccumulator = utteranceAccumulator,
            listener = object : UtteranceListener {
                override fun onUtteranceReady(pcm: FloatArray, code: SttReturnCode) {
                    listener.onUtteranceReady(pcm, code)
                }
            },
            sampleRate = 16000,
            debugLogging = config.debugLoggingEnabled,
            stopRequestedRef = stopRequestedRef
        )
    }

    /**
     * Start the processing pipeline.
     * Delegates to [ProcessorController.start].
     */
    fun start() {
        processorController.start()
    }

    /**
     * Stop the processing pipeline.
     * Delegates to [ProcessorController.stop].
     */
    fun stop() {
        processorController.stop()
    }

    /**
     * Drain remaining frames after the processing loop has stopped.
     * Delegates to [ProcessorController.drainRemainingFrames].
     */
    fun drainRemainingFrames(): FloatArray? {
        return processorController.drainRemainingFrames()
    }

    /**
     * Finalise the current utterance and return the PCM buffer.
     * Delegates to [ProcessorController.stopAndFinalize].
     */
    fun stopAndFinalize(): FloatArray? {
        return processorController.stopAndFinalize()
    }

    /**
     * Reset per-utterance VAD active time.
     * Delegates to [ProcessorController.resetVadActiveMs].
     */
    fun resetVadActiveMs() {
        processorController.resetVadActiveMs()
    }
}

/**
 * Listener interface for processing pipeline events.
 *
 * Delivered on the [ProcessorController] worker thread.
 * Callers must post to their own thread if main-thread delivery is required.
 */
internal fun interface ProcessingListener {
    /**
     * Called when a complete utterance PCM buffer is ready for transcription.
     *
     * @param pcm The accumulated PCM buffer for the utterance.
     * @param code The [SttReturnCode] indicating the result status.
     */
    fun onUtteranceReady(pcm: FloatArray, code: SttReturnCode)
}
