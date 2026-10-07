package com.coc338781.childapp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val isRunning = MonitoringService.isRunning

        if (!isRunning) {
            try {
                val i = Intent(context, MonitoringService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (_: Exception) {}
        }

        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val next = Intent(context, WatchdogReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context, 1001, next,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = SystemClock.elapsedRealtime() + MonitoringService.WATCHDOG_INTERVAL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        } catch (_: Exception) {}
    }
}
