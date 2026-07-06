package dev.barrycade.voicecore.stt

/**
 * CaptureController owns the microphone PCM queue.
 * Responsibilities: AudioCapture start/stop, frame poll.
 *
 * Polling reads directly from AudioCapture.frameQueue, which is populated
 * by the AudioCapture worker thread. CaptureController does NOT maintain
 * a separate queue — that would create an empty queue bug.
 *
 * Forbidden: Whisper, warm-up, inference, STOP logic, VAD, utterance accumulation.
 */
internal class CaptureController(
    private val sampleRate: Int = 16000,
    private val requestedBufferSizeInBytes: Int = 32000
) {
    private var audioCapture: AudioCapture? = null

    /**
     * Start AudioCapture. Returns true on success, false on failure.
     * The capture thread begins enqueuing PCM frames into AudioCapture.frameQueue.
     */
    fun startCapture(): Boolean {
        if (audioCapture != null) return true

        return try {
            val capture = AudioCapture(
                sampleRate = sampleRate,
                requestedBufferSizeInBytes = requestedBufferSizeInBytes
            )
            capture.start()
            audioCapture = capture
            true
        } catch (e: Exception) {
            SttLogger.pcmE("AudioCapture start failed: ${e.message}")
            false
        }
    }

    /**
     * Stop AudioCapture and clear the AudioCapture frame queue.
     */
    fun stopCapture() {
        audioCapture?.stop()
        audioCapture = null
    }

    /**
     * Poll the next frame from AudioCapture.frameQueue.
     * Returns null if queue is empty.
     */
    fun pollFrame(): FloatArray? {
        return audioCapture?.frameQueue?.poll()
    }
}
