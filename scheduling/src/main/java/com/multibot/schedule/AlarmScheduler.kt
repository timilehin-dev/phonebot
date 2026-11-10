package com.multibot.schedule

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
import androidx.work.NetworkState
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
        const val ACTION_ON_ALARM = "com.multibot.app.action.ON_ALARM"
        const val WORK_NAME = "multibot_missed_run_catch_up"
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
            if (timeInMillis < SystemClock.elapsedRealtime()) timeInMillis += TimeUnit.DAYS.toMillis(1)
        }
        val pi = buildPendingIntent(tag, cal.timeInMillis)
        return try {
            // API 31+: exact alarms require canScheduleExactAlarms() — the app
            // gates this via the Settings intent (SCHEDULE_EXACT_ALARM).
            val triggeredAt = if (Build.VERSION.SDK_INT >= 31) {
                AlarmManagerCompat.setExactAndAllowWhileIdle(
                    alarmManager, cal.timeInMillis, pi,
                    /* callbackExecutor = */ null,
                )
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.INTERVAL_DAY,
                    SystemClock.elapsedRealtime() + (cal.timeInMillis - System.currentTimeMillis()),
                    pi,
                )
            }
            triggeredAt
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
            .setRequiredNetworkType(NetworkState.CONNECTED)
            .setBatteryCharging(true)
            .setDeviceIdleMaintenanceWindow(false) // allow during idle, not only maintenance
            .build()
        val request = PeriodicWorkRequestBuilder<MissedRunCatchUpWorker>(
            CATCH_UP_HOURS, TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Receiver target: fires on the exact alarm. Phase 1 opens the app so
     * the foreground nudge service can poke botd; Phase 2 keeps it as a
     * lightweight service wake.
     */
    class OnAlarmFiredReceiver : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val tag = intent.getStringExtra("tag") ?: return
            Log.d("AlarmScheduler", "alarm fired: tag=$tag")
            // Open the app → foreground service → nudge botd tick via TermuxBridge.
            val appIntent = Intent(context, com.multibot.app.MainActivity::class.java).apply {
                putExtra("multibot_routine_tag", tag)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(appIntent)
            // TODO(Phase 2): replace app-open with a foreground service start
            // so the user's UI is not disturbed.
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
            val bridge = com.multibot.termux.TermuxBridge(applicationContext)
            return try {
                bridge.sendRequest("routines.replay_missed")
                Result.success()
            } catch (e: Exception) {
                // Socket down (botd not running) — retry after backoff.
                Result.retry()
            }
        }
    }
}
