package dev.barrycade.voicecore.stt

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * AudioCapture provides a dedicated microphone thread for reading PCM16 mono audio.
 * It publishes each captured frame as a FloatArray into a shared queue.
 * Single output path: FloatArray frames only — no ShortArray callback.
 *
 * ## Thread ownership
 *
 * - [start] and [stop] are called from the caller thread, serialized via [stateLock].
 * - [captureLoop] runs on the AudioCaptureThread (T1).
 * - [frameQueue] is a [ConcurrentLinkedQueue] — safe for single-producer (T1),
 *   multi-consumer (drain thread, processor thread).
 * - [shortBuffer] and [floatBuffer] are assigned once in [start] (caller thread)
 *   before the worker thread starts — safe via happens-before of thread start.
 * - [isRunning] is [@Volatile] — worker thread reads it on each loop iteration;
 *   caller thread writes it under [stateLock].
 *
 * @param sampleRate Audio sample rate in Hz (default 16000).
 * @param requestedBufferSizeInBytes Requested internal AudioRecord buffer size in bytes.
 * @param bufferSizeSamples Size of the read buffer in samples. Controls how many
 *        PCM samples are read per AudioRecord.read() call. Must be >= 1024 and <= 16000.
 *        Default 4000 (0.25s at 16kHz).
 */
internal class AudioCapture(
    private val sampleRate: Int = 16000,
    private val requestedBufferSizeInBytes: Int,
    private val bufferSizeSamples: Int = 4000
) {
    private val stateLock = Any()

    @Volatile
    private var isRunning: Boolean = false

    private var audioRecord: AudioRecord? = null
    private var workerThread: Thread? = null
    private var shortBuffer: ShortArray? = null
    private var floatBuffer: FloatArray? = null

    val frameQueue: ConcurrentLinkedQueue<FloatArray> = ConcurrentLinkedQueue()

    fun getQueue(): ConcurrentLinkedQueue<FloatArray> = frameQueue

    fun clearQueue() {
        frameQueue.clear()
    }

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

            SttLogger.pcm("[CAPTURE] AudioCapture.start() — invoking AudioRecord.startRecording() synchronously")
            try {
                ar.startRecording()
            } catch (e: Exception) {
                ar.release()
                throw IllegalStateException("Failed to start audio recording", e)
            }
            SttLogger.pcm("[CAPTURE] AudioRecord.startRecording() returned OK")

            audioRecord = ar
            isRunning = true

            val runnable = Runnable {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                captureLoop(bufferSizeSamples)
            }
            val thread = Thread(runnable, "AudioCaptureThread")
            workerThread = thread
            thread.start()

            SttLogger.pcm("[CAPTURE] Capture started [Rate: $sampleRate, ReadBuffer: $bufferSizeSamples samples, AudioRecordBuffer: $finalBufferSizeInBytes bytes]")
        }
    }

    private fun captureLoop(bufferSizeSamples: Int) {
        shortBuffer = ShortArray(bufferSizeSamples)
        floatBuffer = FloatArray(bufferSizeSamples)

        while (isRunning) {
            val ar = audioRecord ?: break
            val readCount = ar.read(shortBuffer!!, 0, shortBuffer!!.size)

            if (readCount > 0) {
                if (!isRunning) break
                for (index in 0 until readCount) {
                    floatBuffer!![index] = shortBuffer!![index].toFloat() / Short.MAX_VALUE
                }
                val floatFrame = floatBuffer!!.copyOf(readCount)
                val running = isRunning
                if (running) {
                    frameQueue.offer(floatFrame)
                    SttLogger.pcmD("enqueue frame, size=${floatFrame.size}")
                }
            } else if (readCount < 0) {
                handleReadError(readCount)
                if (readCount == AudioRecord.ERROR_DEAD_OBJECT) break
            }
        }
        SttLogger.pcmD("Worker thread exiting")
    }

    private fun handleReadError(errorCode: Int) {
        val message = when (errorCode) {
            AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
            AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
            AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
            else -> "Unknown error ($errorCode)"
        }
        SttLogger.pcmE("Read error: $message")
    }

    fun stop() {
        // ── Phase 1: signal stop, stop AudioRecord, extract thread ref ──
        // stateLock is NOT held across join().
        val threadToJoin: Thread?
        synchronized(stateLock) {
            if (!isRunning) return
            isRunning = false

            try {
                val ar = audioRecord
                if (ar != null && ar.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    ar.stop()
                }
            } catch (e: Exception) {
                SttLogger.pcmE("Error stopping AudioRecord", e)
            }

            threadToJoin = workerThread
            workerThread = null
        }

        // ── Phase 2: join worker thread OUTSIDE stateLock ───────────────
        // Self-join guard: a thread cannot join itself.
        if (threadToJoin != null && threadToJoin !== Thread.currentThread()) {
            try {
                threadToJoin.join(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                SttLogger.pcmW("Interrupted during join")
            }
        }

        // ── Phase 3: release resources under stateLock ──────────────────
        synchronized(stateLock) {
            audioRecord?.release()
            audioRecord = null
            frameQueue.clear()
            SttLogger.pcmD("Capture stopped")
        }
    }
}
