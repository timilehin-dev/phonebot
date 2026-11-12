package com.phonebot.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.phonebot.app.bridge.BridgeRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that brings the app back onto the bridge after boot.
 *
 * Termux:Boot owns starting sshd + the botd daemon; the app's half of the
 * reboot path is to reconnect the persistent subscriber and confirm the
 * daemon answers (`botd.cli ping`), retrying briefly because Termux may still
 * be coming up. It stops itself once healthy or after a bounded number of
 * attempts, so it never lingers in the foreground forever.
 *
 * Approval alerts are NOT posted from this service: those are raised by
 * `com.phonebot.app.bridge.SystemApprovalNotifier` (high-importance
 * `approvals` channel, deep-links into the Approvals view) as
 * `needs-approval` bus events arrive over the persistent bridge.
 */
class BotdNudgeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var nudgeJob: Job? = null

    companion object {
        const val CHANNEL_ID = "botd_nudge"
        const val NOTIFICATION_ID = 1

        private const val TAG = "PhonebotNudge"
        private const val MAX_ATTEMPTS = 5
        private const val RETRY_BASE_MS = 3_000L
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
        if (nudgeJob?.isActive != true) {
            val reason = intent?.getStringExtra("reason") ?: "manual"
            nudgeJob = scope.launch { nudgeThenStop(reason) }
        }
        return START_STICKY
    }

    /**
     * Reconnect the persistent subscriber, then confirm botd answers. Retries
     * with linear backoff so a slow Termux:Boot cannot race the nudge.
     */
    private suspend fun nudgeThenStop(reason: String) {
        BridgeRuntime.start()
        repeat(MAX_ATTEMPTS) { attempt ->
            if (BridgeRuntime.pingBotd()) {
                Log.i(TAG, "botd reachable ($reason, attempt ${attempt + 1})")
                stopSelf()
                return
            }
            delay(RETRY_BASE_MS * (attempt + 1))
        }
        Log.w(TAG, "botd unreachable after $MAX_ATTEMPTS attempts ($reason)")
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
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
            .setContentText("Reconnecting to botd…")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
