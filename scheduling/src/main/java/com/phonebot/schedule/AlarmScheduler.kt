package com.phonebot.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.AlarmManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * AlarmScheduler — API 31+ exact alarms for routine firing + WorkManager
 * catch-up for missed runs.
 *
 * Flow:
 *  1. [scheduleExactAlarm] sets an [AlarmManager.setExactAndAllowWhileIdle]
 *     (exact, API 31+ semantics; SCHEDULE_EXACT_ALARM is gated by the app
 *     UI before scheduling).
 *  2. The pending intent fires [OnAlarmFiredReceiver] → opens the app
 *     (or wakes the foreground nudge service) → nudges botd with a
 *     "tick" via TermuxBridge so botd replays its due routines.
 *  3. [MissedRunCatchUpWorker] (WorkManager periodic, CHARGING + IDLE
 *     constraint) asks botd "replay missed" on every pass so exact-alarm
 *     losses (Doze, reboot, alarm denial) self-heal.
 */
class AlarmScheduler(private val context: Context) {

    companion object {
        const val ACTION_ON_ALARM = "com.phonebot.app.action.ON_ALARM"
        const val WORK_NAME = "phonebot_missed_run_catch_up"
        private const val CATCH_UP_HOURS = 2
    }

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * Schedule an exact daily (or weekly) alarm.
     *
     * @param hour   0-23
     * @param minute 0-59
     * @param dayOfWeek optional Calendar.DAY_OF_WEEK (1=SUN..7=SAT)
     * @param tag    stable id — used as the pending-intent requestCode + work tag
     */
    fun scheduleExactAlarm(hour: Int, minute: Int, dayOfWeek: Int? = null, tag: String): Boolean {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            dayOfWeek?.let { set(Calendar.DAY_OF_WEEK, it) }
            if (timeInMillis <= System.currentTimeMillis()) {
                timeInMillis += TimeUnit.DAYS.toMillis(1)
            }
        }
        val pi = buildPendingIntent(tag, cal.timeInMillis)
        return try {
            // AlarmManagerCompat applies the right API-level semantics; the app
            // gates exact-alarm permission via Settings (SCHEDULE_EXACT_ALARM).
            AlarmManagerCompat.setExactAndAllowWhileIdle(
                alarmManager, AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi,
            )
            true
        } catch (e: SecurityException) {
            Log.w("AlarmScheduler", "SCHEDULE_EXACT_ALARM denied for tag=$tag", e)
            false
        }
    }

    fun cancelAlarm(tag: String) {
        val pi = buildPendingIntent(tag, 0)
        alarmManager.cancel(pi)
    }

    private fun buildPendingIntent(tag: String, fireAtMs: Long): PendingIntent {
        val i = Intent(context, OnAlarmFiredReceiver::class.java).apply {
            putExtra("tag", tag)
            putExtra("fire_at_ms", fireAtMs)
            action = ACTION_ON_ALARM
        }
        return PendingIntent.getBroadcast(
            context, tag.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Enqueue the periodic catch-up job. Constraint: device CHARGING + IDLE,
     * which is the only reliable window where Termux's shell can reach botd
     * without waking the user's screen.
     */
    fun enqueueMissedRunCatchUp() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresCharging(true)
            .setRequiresDeviceIdle(true)
            .build()
        val request = PeriodicWorkRequestBuilder<MissedRunCatchUpWorker>(
            CATCH_UP_HOURS.toLong(), TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15L, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Receiver target: fires on the exact alarm and wakes the nudge service
     * (which reconnects the bridge and pokes botd), without opening the UI.
     */
    class OnAlarmFiredReceiver : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val tag = intent.getStringExtra("tag") ?: return
            Log.d("AlarmScheduler", "alarm fired: tag=$tag")
            // Start the app's foreground service by class name: :scheduling
            // must not depend on :app (that would be a circular dependency).
            val service = Intent().apply {
                setClassName(context.packageName, "com.phonebot.app.service.BotdNudgeService")
                putExtra("reason", "alarm:$tag")
            }
            context.startForegroundService(service)
        }
    }

    /**
     * Periodic WorkManager job: asks botd to replay any routines that
     * missed their exact-alarm window (Doze, reboot, permission denial).
     */
    class MissedRunCatchUpWorker(
        ctx: Context,
        params: androidx.work.WorkerParameters,
    ) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val bridge = com.phonebot.termux.TermuxBridge(applicationContext)
            return try {
                // `routines.replay_missed` is NOT a botd IPC control message.
                // Instead we trigger a bot run with a special trigger that
                // tells the botd AgentLoop to re-run due-but-missed cron
                // windows in its own session. The botd `scheduler.run_trigger`
                // entry point handles this; the trigger object encodes the
                // mode so the isolated session knows to replay.
                bridge.runBot("chief", """{"trigger":"catch_up"}""")
                Result.success()
            } catch (e: Exception) {
                // Socket down (botd not running, API 33 without UnixDomainSocket) —
                // retry after backoff.
                Result.retry()
            }
        }
    }
}
