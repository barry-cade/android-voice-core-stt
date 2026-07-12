package dev.barrycade.voicecore.stt

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Fake [AudioCapture] for unit tests that validate synchronous start semantics.
 *
 * Provides deterministic control over recording state without Android
 * [android.media.AudioRecord] dependency. Runs in pure JVM tests.
 *
 * Synchronous semantics: [start] sets [isRecording] immediately and
 * increments [startCallCount] — no background threads, no deferred execution.
 */
internal class FakeAudioCapture {

    /** True after [start] and before [stop]. Set synchronously. */
    @Volatile
    var isRecording: Boolean = false
        private set

    /** Number of times [start] was called. */
    var startCallCount: Int = 0
        private set

    /** Number of times [stop] was called. */
    var stopCallCount: Int = 0
        private set

    /** Simulated frame queue, populated by tests. */
    val frameQueue: ConcurrentLinkedQueue<FloatArray> = ConcurrentLinkedQueue()

    /**
     * Start recording. Synchronous — sets state immediately.
     */
    fun start() {
        isRecording = true
        startCallCount++
    }

    /**
     * Stop recording. Synchronous — sets state immediately.
     */
    fun stop() {
        isRecording = false
        stopCallCount++
    }

    /** Clear all pending frames. */
    fun clearQueue() {
        frameQueue.clear()
    }

    /** Add a PCM frame to the queue. */
    fun addFrame(frame: FloatArray) {
        frameQueue.add(frame)
    }

    /** Reset all tracking state. */
    fun reset() {
        isRecording = false
        startCallCount = 0
        stopCallCount = 0
        frameQueue.clear()
    }
}
