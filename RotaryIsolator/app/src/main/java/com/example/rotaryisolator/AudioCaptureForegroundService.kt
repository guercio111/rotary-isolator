package com.example.rotaryisolator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class AudioCaptureForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        android.util.Log.i("FREQUALIZER_CAPTURE", "SERVICE onCreate")
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "FREQUALIZER audio capture", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("FREQUALIZER • REVERB ATTIVO")
            .setContentText("Elaborazione audio in background")
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // A restarted sticky service would not receive the one-shot MediaProjection
    // consent token again. Never resurrect capture without a fresh user grant.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.i("FREQUALIZER_CAPTURE", "SERVICE onStartCommand startId=$startId flags=$flags intentPresent=${intent != null}")
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        android.util.Log.w("FREQUALIZER_CAPTURE", "SERVICE onTaskRemoved")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        android.util.Log.w("FREQUALIZER_CAPTURE", "SERVICE onDestroy")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "frequalizer_capture"
        private const val NOTIFICATION_ID = 7422
    }
}
