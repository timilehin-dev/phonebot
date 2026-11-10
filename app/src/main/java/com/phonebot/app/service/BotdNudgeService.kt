package com.phonebot.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Foreground service that nudges botd after boot and on demand.
 *
 * Phase 1: posts a dataSync notification so the boot wiring is testable
 * without a live Termux socket. The notification channels must be created
 * before startForeground — done lazily here for API 26+.
 *
 * TODO(Phase 2):
 *  - start Termux (Termux:Boot profile) + the botd daemon
 *  - ping the @phonebot socket to confirm liveness
 *  - cancel the service after a successful heartbeat loop
 */
class BotdNudgeService : Service() {

    companion object {
        const val CHANNEL_ID = "botd_nudge"
        const val NOTIFICATION_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // TODO(Phase 2): poke botd tick via TermuxBridge; heartbeat loop.
        return START_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            mgr.createNotificationChannel(
                android.app.NotificationChannel(
                    CHANNEL_ID,
                    "botd nudge",
                    android.app.NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, com.phonebot.app.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Phonebot")
            .setContentText("Nudging botd…")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
