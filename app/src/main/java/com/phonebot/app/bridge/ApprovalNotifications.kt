package com.phonebot.app.bridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.phonebot.app.MainActivity
import com.phonebot.termux.ApprovalRequest
import com.phonebot.termux.ApprovalRisk

/** Posts/cancels the system notification for one pending approval. */
interface ApprovalNotifier {
    fun post(request: ApprovalRequest)
    fun cancel(corrId: String)
}

/** Observer the bridge runtime notifies when the approvals inbox changes. */
interface ApprovalObserver {
    fun onPending(pending: List<ApprovalRequest>)
    fun onResolved(corrId: String)
}

/**
 * Pure notification decision logic (no Android), so the no-duplicate and
 * already-resolved rules are unit-testable:
 *
 *  - every `corr_id` is announced at most once while it is pending;
 *  - a `corr_id` that has been resolved (approve/deny) is never announced,
 *    even if a stale `needs-approval` frame arrives afterwards.
 */
class ApprovalNotificationPlanner {
    private val notified = mutableSetOf<String>()
    private val resolved = mutableSetOf<String>()

    /** Returns only approvals that need a notification now. */
    @Synchronized
    fun plan(pending: List<ApprovalRequest>): List<ApprovalRequest> =
        pending.filter { request ->
            request.corrId !in resolved && request.corrId !in notified && notified.add(request.corrId)
        }

    @Synchronized
    fun resolve(corrId: String) {
        resolved += corrId
        notified -= corrId
    }

    @Synchronized
    fun reset() {
        notified.clear()
        resolved.clear()
    }
}

/** Connects pending-approval changes to notifications, with dedupe/resolution. */
class ApprovalNotificationCoordinator(
    private val notifier: ApprovalNotifier,
    private val planner: ApprovalNotificationPlanner = ApprovalNotificationPlanner(),
) : ApprovalObserver {

    override fun onPending(pending: List<ApprovalRequest>) {
        planner.plan(pending).forEach(notifier::post)
    }

    override fun onResolved(corrId: String) {
        planner.resolve(corrId)
        notifier.cancel(corrId)
    }
}

/**
 * Android implementation: one high-importance `approvals` channel, one
 * notification per `corr_id`, deep-linking into the Approvals view.
 */
class SystemApprovalNotifier(
    private val context: Context,
    private val channelId: String = CHANNEL_ID,
) : ApprovalNotifier {

    override fun post(request: ApprovalRequest) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager)
        if (!canPost(manager)) return
        manager.notify(notificationId(request.corrId), build(request))
    }

    override fun cancel(corrId: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(notificationId(corrId))
    }

    private fun canPost(manager: NotificationManager): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Approvals",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Bot actions waiting for your approval"
                enableVibration(true)
                setShowBadge(true)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun build(request: ApprovalRequest): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_VIEW, MainActivity.VIEW_APPROVALS)
            putExtra(MainActivity.EXTRA_CORR_ID, request.corrId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId(request.corrId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when (request.risk) {
            ApprovalRisk.Critical -> "Critical approval needed · ${request.bot}"
            else -> "Approval needed · ${request.bot}"
        }
        return Notification.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(request.command)
            .setStyle(Notification.BigTextStyle().bigText(request.command))
            .setPriority(Notification.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    /** One stable id per corr_id, kept out of the nudge service's id space. */
    private fun notificationId(corrId: String): Int =
        NOTIFICATION_ID_BASE + ((corrId.hashCode() and 0x7FFFFFFF) % 100_000)

    companion object {
        const val CHANNEL_ID = "approvals"
        private const val NOTIFICATION_ID_BASE = 20_000
    }
}
