package dev.barrycade.voicecore.stt

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * AudioCapture provides a dedicated microphone thread for reading PCM16 mono audio.
 * It publishes each captured frame as a FloatArray into a shared queue and also keeps
 * the existing listener callback path for compatibility with the current STT pipeline.
 */
class AudioCapture(
    private val sampleRate: Int = 16000,
    private val requestedBufferSizeInBytes: Int
) {
    private var listener: ((ShortArray) -> Unit)? = null
    private val stateLock = Any()

    @Volatile
    private var isRunning: Boolean = false

    private var audioRecord: AudioRecord? = null
    private var workerThread: Thread? = null

    val frameQueue: ConcurrentLinkedQueue<FloatArray> = ConcurrentLinkedQueue()

    fun setOnAudioFrameListener(l: (ShortArray) -> Unit) {
        synchronized(stateLock) {
            listener = l
        }
    }

    fun getQueue(): ConcurrentLinkedQueue<FloatArray> = frameQueue

    @SuppressLint("MissingPermission")
    fun start() {
        synchronized(stateLock) {
            if (isRunning) return

            frameQueue.clear()

            val minBufferSizeInBytes = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            if (minBufferSizeInBytes <= 0) {
                throw IllegalStateException("Invalid AudioRecord parameters: minBufferSize=$minBufferSizeInBytes")
            }

            val finalBufferSizeInBytes = maxOf(requestedBufferSizeInBytes, minBufferSizeInBytes)
            val internalBufferSizeInBytes = finalBufferSizeInBytes * 4

            val ar = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    internalBufferSizeInBytes
                )
            } catch (e: Exception) {
                throw IllegalStateException("Failed to create AudioRecord instance", e)
            }

            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                ar.release()
                throw IllegalStateException("AudioRecord failed to initialize. Check permissions or MIC availability.")
            }

            try {
                ar.startRecording()
            } catch (e: Exception) {
                ar.release()
                throw IllegalStateException("Failed to start audio recording", e)
            }

            audioRecord = ar
            isRunning = true

            val readBufferSamples = finalBufferSizeInBytes / 2
            workerThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                captureLoop(readBufferSamples)
            }, "AudioCaptureThread").apply {
                start()
            }

            Log.d("AudioCapture", "Capture started [Rate: $sampleRate, Buffer: $finalBufferSizeInBytes bytes]")
        }
    }

    private fun captureLoop(bufferSizeSamples: Int) {
        val buffer = ShortArray(bufferSizeSamples)

        while (isRunning) {
            val ar = audioRecord ?: break
            val readCount = ar.read(buffer, 0, buffer.size)

            if (readCount > 0) {
                val capturedFrame = buffer.copyOf(readCount)
                val floatFrame = FloatArray(readCount) { index ->
                    capturedFrame[index].toFloat() / Short.MAX_VALUE
                }
                frameQueue.offer(floatFrame)

                synchronized(stateLock) {
                    listener?.invoke(capturedFrame)
                }
            } else if (readCount < 0) {
                handleReadError(readCount)
                if (readCount == AudioRecord.ERROR_DEAD_OBJECT) break
            }
        }
        Log.d("AudioCapture", "Worker thread exiting")
    }

    private fun handleReadError(errorCode: Int) {
        val message = when (errorCode) {
            AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
            AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
            AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
            else -> "Unknown error ($errorCode)"
        }
        Log.e("AudioCapture", "Read error: $message")
    }

    fun stop() {
        synchronized(stateLock) {
            if (!isRunning) return
            isRunning = false

            try {
                audioRecord?.let {
                    if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        it.stop()
                    }
                }
            } catch (e: Exception) {
                Log.e("AudioCapture", "Error stopping AudioRecord", e)
            }

            try {
                workerThread?.join(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.w("AudioCapture", "Interrupted during join")
            }

            audioRecord?.release()
            audioRecord = null
            workerThread = null
            Log.d("AudioCapture", "Capture stopped")
        }
    }
}
