package com.example.hermesassistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Keeps the app process alive so the WebSocket (owned by MainActivity)
 * stays connected while the app is in the background. Without this,
 * Samsung and other OEMs freeze backgrounded apps: the TCP connection
 * lingers as a zombie socket and notify events are never processed.
 *
 * START_STICKY: if the OS kills the service, it is restarted.
 */
class HermesForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "hermes_service"
        // Foreground-service notification ID + PendingIntent request code.
        // Kept in a reserved range so session notify/reply notifications
        // (IDs 100+ / 200+) never collide with it.
        const val NOTIFICATION_ID = 1
        const val REQUEST_CODE = 100000
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        return START_STICKY
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Hermes Assistant connection",
                NotificationManager.IMPORTANCE_LOW // silent, no badge/sound
            ).apply {
                description = "Keeps Hermes Assistant alive to receive notifications"
                setShowBadge(false)
            }
        )

        // Tapping the persistent notification opens the app
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this,
            REQUEST_CODE,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Hermes Assistant is running")
            .setContentText("Listening for Hermes events")
            .setOngoing(true) // not dismissible — it's the app's lifeline
            .setContentIntent(pi)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Keep it simple: the activity restarts it on next launch.
    }
}
