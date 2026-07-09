package dev.barrycade.voicecore.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import dev.barrycade.voicecore.stt.AudioCapture

class AudioTestService : Service() {
    private val audioCapture = AudioCapture(
        sampleRate = 16000,
        requestedBufferSizeInBytes = 32000
    )
    private var workerThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Test",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Audio Test")
                .setContentText("Recording audio")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("Audio Test")
                .setContentText("Recording audio")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(1, notification)
        }

                audioCapture.start()

        // Poll FloatArray frames from the queue
        val runnable = Runnable {
            runAudioPollLoop()
        }
        val thread = Thread(runnable, "AudioTestPollThread")
        workerThread = thread
        thread.start()
        return START_STICKY
    }

    override fun onDestroy() {
        workerThread?.interrupt()
        workerThread = null
        audioCapture.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Polls FloatArray frames from the audio capture queue.
     * Runs on the worker thread. Exits when the thread is interrupted.
     */
    private fun runAudioPollLoop() {
        while (!Thread.currentThread().isInterrupted) {
            val frame = audioCapture.frameQueue.poll()
            if (frame != null) {
                Log.d(
                    "AudioTest",
                    "frame=${frame.size} samples, t=${System.currentTimeMillis()}"
                )
            } else {
                try {
                    Thread.sleep(10L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "audio_test_service"
    }
}

