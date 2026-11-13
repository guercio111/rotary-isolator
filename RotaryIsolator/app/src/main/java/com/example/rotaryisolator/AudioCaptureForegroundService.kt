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
        recordLifecycleEvent("onCreate: foreground promotion completed; sdk=${Build.VERSION.SDK_INT}")
    }

    private fun recordLifecycleEvent(event: String) {
        val prefs = getSharedPreferences("rotary_options_v3", MODE_PRIVATE)
        val stamp = android.os.SystemClock.elapsedRealtime()
        val old = prefs.getString("capture_service_diag", "").orEmpty()
        val next = (old.lines().filter { it.isNotBlank() } + "t+${stamp}ms $event").takeLast(30).joinToString("\\n")
        prefs.edit().putString("capture_service_diag", next).commit()
        android.util.Log.i("FREQUALIZER_CAPTURE_SERVICE", "t+${stamp}ms $event")
    }

    // A restarted sticky service would not receive the one-shot MediaProjection
    // consent token again. Never resurrect capture without a fresh user grant.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        recordLifecycleEvent("onStartCommand: startId=$startId flags=$flags")
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        recordLifecycleEvent("onTaskRemoved")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        recordLifecycleEvent("onDestroy")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "frequalizer_capture"
        private const val NOTIFICATION_ID = 7422
    }
}
