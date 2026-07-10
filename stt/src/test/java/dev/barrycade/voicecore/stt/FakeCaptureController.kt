package dev.barrycade.voicecore.stt

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Fake [AudioSource] for unit tests.
 *
 * Provides deterministic PCM frames via a pre-populated queue.
 * No Android [AudioCapture] dependency — runs in pure JVM tests.
 */
internal class FakeCaptureController : AudioSource {

    /** Pre-populated frame queue. Add frames before [startCapture]. */
    val frameQueue: ConcurrentLinkedQueue<FloatArray> = ConcurrentLinkedQueue()

    /** True after [startCapture], false after [stopCapture]. */
    var isStarted: Boolean = false
        private set

    /** When true, [startCapture] returns false. */
    var failOnStart: Boolean = false

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
        return frameQueue.poll()
    }

    override fun clearQueue() {
        frameQueue.clear()
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
    fun reset() {
        frameQueue.clear()
        isStarted = false
        failOnStart = false
    }
}
