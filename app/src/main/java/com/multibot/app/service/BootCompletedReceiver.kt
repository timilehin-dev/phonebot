package com.multibot.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Fires on [Intent.ACTION_BOOT_COMPLETED] to start the foreground
 * [BotdNudgeService], which in turn pokes botd via Termux:Boot.
 *
 * Without Termux:Boot the Termux shell (and therefore botd) will not
 * auto-start after a device reboot; this receiver is the app's half of
 * the "late-but-never-lost" guarantee that L3 (WorkManager catch-up)
 * completes on the scheduling side.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i(TAG, "BOOT_COMPLETED received — starting BotdNudgeService")
        context.startForegroundService(
            Intent(context, BotdNudgeService::class.java).apply {
                putExtra("reason", "boot")
            },
        )
    }

    companion object {
        private const val TAG = "MultibotBoot"
    }
}
