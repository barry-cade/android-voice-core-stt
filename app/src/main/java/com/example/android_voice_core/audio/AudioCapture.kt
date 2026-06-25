package com.example.android_voice_core.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

class AudioCapture(
    private val callback: (ShortArray) -> Unit
) {
    @Volatile
    var isRunning: Boolean = false
        private set

    private var audioRecord: AudioRecord? = null
    private var workerThread: Thread? = null

    fun start() {
        if (isRunning) return

        val bufferSize = AudioRecord.getMinBufferSize(
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufferSize <= 0) return

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord?.release()
            audioRecord = null
            return
        }

        audioRecord?.startRecording()

        isRunning = true
        workerThread = Thread {
            val buffer = ShortArray(bufferSize / 2)
            while (isRunning) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    callback(buffer.copyOf(read))
                }
            }
        }
        workerThread?.start()
    }

    fun stop() {
        if (!isRunning) return

        isRunning = false
        workerThread?.join(1000)
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        workerThread = null
    }
}
