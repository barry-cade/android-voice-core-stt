package dev.barrycade.voicecore.wuw

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Foreground service that keeps the wake-word listening process alive.
 *
 * Runs in the foreground with a low-importance notification so the system
 * does not kill the process while listening for the wake word.
 *
 * The service owns the [WakeWordSessionManager] lifecycle. It creates
 * the session manager in [onCreate] and destroys it in [onDestroy].
 *
 * Communication with the service is via intent extras:
 * - "action": "start" or "stop"
 * - "on_detection_action": (optional) intent action string to broadcast
 *   when the wake word is detected.
 */
class WakeWordService : Service() {

    companion object {
        private const val CHANNEL_ID = "wake_word_service"
        private const val NOTIFICATION_ID = 2

        /** Intent action to start listening. */
        const val ACTION_START = "dev.barrycade.voicecore.wuw.START"

        /** Intent action to stop listening. */
        const val ACTION_STOP = "dev.barrycade.voicecore.wuw.STOP"

        /** Extra key for the on-detection broadcast action. */
        const val EXTRA_ON_DETECTION_ACTION = "on_detection_action"

        /** Extra key for the similarity threshold. */
        const val EXTRA_THRESHOLD = "threshold"
    }

    private var sessionManager: WakeWordSessionManager? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val onDetectionAction = intent.getStringExtra(EXTRA_ON_DETECTION_ACTION)
                val threshold = intent.getFloatExtra(EXTRA_THRESHOLD, 0.7f)

                startForegroundService()
                startWakeWordListening(onDetectionAction, threshold)
            }
            ACTION_STOP -> {
                stopWakeWordListening()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        sessionManager?.destroy()
        sessionManager = null
        super.onDestroy()
    }

    private fun startForegroundService() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Wake Word")
                .setContentText("Listening for wake word...")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Wake Word")
                .setContentText("Listening for wake word...")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Wake Word",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startWakeWordListening(onDetectionAction: String?, threshold: Float) {
        val manager = WakeWordSessionManager(
            context = this,
            onDetectionAction = onDetectionAction,
            threshold = threshold
        )
        sessionManager = manager
        manager.startListening()
    }

    private fun stopWakeWordListening() {
        sessionManager?.stopListening()
        sessionManager = null
    }
}
