package com.example.rotaryisolator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Binder

class AudioCaptureForegroundService : Service() {
    inner class LocalBinder : Binder() {
        fun getService(): AudioCaptureForegroundService = this@AudioCaptureForegroundService
    }

    private val localBinder = LocalBinder()
    @Volatile private var foregroundReady = false

    fun isForegroundReady(): Boolean = foregroundReady

    override fun onCreate() {
        super.onCreate()
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
        // The Activity waits for this service connection before requesting MediaProjection.
        foregroundReady = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder = localBinder

    override fun onDestroy() {
        foregroundReady = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "frequalizer_capture"
        private const val NOTIFICATION_ID = 7422
    }
}
