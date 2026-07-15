package dev.barrycade.voicecore.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.barrycade.voicecore.AppLogger
import dev.barrycade.voicecore.AppLogCode
/**
 * Audio test service stub.
 *
 * Preserved for future diagnostics integration.
 * Use [SpeechToText] directly via JSON config for STT operations.
 */
class AudioTestService : Service() {

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
        val notification: Notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Audio Test")
                .setContentText("Recording audio")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build()
        } else {
            @Suppress("DEPRECATION")
            notification = Notification.Builder(this)
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

        AppLogger.log(AppLogCode.AUDIO_TEST_SERVICE_STARTED)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "audio_test_service"
    }
}

