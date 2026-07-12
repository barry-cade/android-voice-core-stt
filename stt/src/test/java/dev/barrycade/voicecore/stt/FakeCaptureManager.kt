package dev.barrycade.voicecore.stt

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Fake [CaptureManager] for unit tests.
 *
 * Provides deterministic PCM frames via a pre-populated queue.
 * No Android [AudioCapture] dependency — runs in pure JVM tests.
 * Implements [AudioSource] for compatibility with [ProcessorController].
 *
 * Simulates the dual-mode frame capture:
 * - [begin]: starts the drain thread (or frames can be added manually)
 * - [finalize]: returns all buffered frames as raw PCM
 * - [pollFrame]: buffers frame into session AND returns it for VAD
 */
internal class FakeCaptureManager(
    private val sampleRate: Int = 16000,
    private val bufferSizeBytes: Int = 32000
) : SessionManager {

    /** Pre-populated frame queue. Add frames via [addFrame] or [addSilenceFrame]/[addSpeechFrame]. */
    val frameQueue: ConcurrentLinkedQueue<FloatArray> = ConcurrentLinkedQueue()

    /** Session buffer: accumulates frames since [begin]. */
    private val sessionBuffer = mutableListOf<Float>()

    /** True after [begin] and before [finalize] or [reset]. */
    var sessionActive: Boolean = false
        private set

    /** When true, [startCapture] returns false. */
    var failOnStart: Boolean = false

    /** True after [startCapture], false after [stopCapture]. */
    var isStarted: Boolean = false
        internal set

    /**
     * True when STT processing is allowed to consume PCM frames.
     * Set to false in [beginPcmCapture] and true in [beginSttProcessing].
     * Guards the drain-thread simulation from buffering frames before
     * the STT pipeline is officially active.
     */
    @Volatile
    var sttActive: Boolean = false
        internal set

    /**
     * Stores the [DrainMode] from the most recent [begin] call.
     * Used by [beginSttProcessing] for drain-mode dispatching.
     */
    private var currentDrainMode: DrainMode = DrainMode.DRAIN_FROM_NEXT_FRAME

    override fun startCapture(): Boolean {
        if (failOnStart) return false
        isStarted = true
        return true
    }

    override fun stopCapture() {
        isStarted = false
    }

    override fun pollFrame(): FloatArray? {
        if (!isStarted) return null
        val frame = frameQueue.poll()
        if (frame != null && sessionActive) {
            for (sample in frame) {
                sessionBuffer.add(sample)
            }
        }
        return frame
    }

    override fun clearQueue() {
        frameQueue.clear()
    }

    // ── CaptureManager session methods ─────────────────────────────────

    /**
     * Begin a session: clear buffer and start accumulating.
     *
     * Delegates to [beginPcmCapture] and [beginSttProcessing] in sequence.
     *
     * @param mode Drain mode that determines whether pre-existing frames
     *        in the queue are included in the session buffer.
     */
    override fun begin(mode: DrainMode) {
        currentDrainMode = mode
        beginPcmCapture()
        beginSttProcessing()
    }

    /**
     * Start PCM capture synchronously.
     *
     * Temporary placeholder — matches real CaptureManager.beginPcmCapture().
     * Phase 2 will refine the split.
     */
    override fun beginPcmCapture() {
        sessionBuffer.clear()
        sttActive = false
        sessionActive = true
        // Capture starts lazily — matching real CaptureManager.beginPcmCapture() behaviour.
        if (!isStarted) {
            isStarted = true
        }
        SttLogger.pcm("[CAPTURE] FakeCaptureManager.beginPcmCapture() — capture started")
    }

    /**
     * Start STT processing (drain-thread simulation).
     *
     * Uses [currentDrainMode] to dispatch to the correct drain strategy.
     * Temporary placeholder — Phase 2 will refine the split.
     */
    override fun beginSttProcessing() {
        sttActive = true
        when (currentDrainMode) {
            DrainMode.DRAIN_FROM_HEAD -> {
                // Drain all pre-existing frames into the session buffer.
                // Only process frames if STT is active.
                while (sttActive) {
                    val frame = frameQueue.poll() ?: break
                    for (sample in frame) {
                        sessionBuffer.add(sample)
                    }
                }
            }
            DrainMode.DRAIN_FROM_NEXT_FRAME -> {
                // Discard any pre-existing frames.
                frameQueue.clear()
            }
        }
    }

    /**
     * Finalize the session: drain remaining queue frames and return raw PCM.
     * After this call, the session buffer is cleared and capture is stopped.
     */
    override fun finalize(): FloatArray {
        sessionActive = false
        while (true) {
            val frame = frameQueue.poll() ?: break
            for (sample in frame) {
                sessionBuffer.add(sample)
        }
}
        val result = sessionBuffer.toFloatArray()
        sessionBuffer.clear()
        isStarted = false
        return result
    }

    /**
     * Reset session state without clearing the frame queue.
     */
    override fun reset() {
        sessionActive = false
        sessionBuffer.clear()
        frameQueue.clear()
    }

    /**
     * Shutdown — clears session and queue, stops capture.
     */
    override fun shutdown() {
        sessionActive = false
        sessionBuffer.clear()
        frameQueue.clear()
        isStarted = false
    }

    /**
     * Restart capture after a prior [finalize] stopped it.
     */
    override fun restartCapture() {
        if (isStarted) return
        isStarted = true
    }

    // ── Frame injection helpers ─────────────────────────────────────────

    /** Add a single PCM frame to the queue. */
    fun addFrame(frame: FloatArray) {
        frameQueue.add(frame)
    }

    /** Add a single PCM frame of silence of the given size. */
    fun addSilenceFrame(size: Int = 320) {
        frameQueue.add(FloatArray(size))
    }

    /** Add a single PCM frame of speech-level amplitude. */
    fun addSpeechFrame(size: Int = 320) {
        frameQueue.add(FloatArray(size) { 0.5f })
    }

    /** Add multiple silence frames. */
    fun addSilenceFrames(count: Int, size: Int = 320) {
        repeat(count) { addSilenceFrame(size) }
    }

    /** Add multiple speech frames. */
    fun addSpeechFrames(count: Int, size: Int = 320) {
        repeat(count) { addSpeechFrame(size) }
    }

    /** Clear all frames and reset state. */
    fun resetAll() {
        frameQueue.clear()
        sessionBuffer.clear()
        sessionActive = false
        isStarted = false
        failOnStart = false
    }
}
