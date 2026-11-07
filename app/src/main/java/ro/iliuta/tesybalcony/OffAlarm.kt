package ro.iliuta.tesybalcony

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Exact wake-up alarm for the end of a heating cycle.
 *
 * WorkManager alone may defer the OFF task for a long time while the phone is
 * in Doze. An allow-while-idle alarm fires on time (or, without the exact-alarm
 * permission, close to it) and starts an expedited OFF worker. The delayed
 * WorkManager request stays in place as a backup.
 */
object OffAlarm {
    const val ACTION = "ro.iliuta.tesybalcony.ACTION_FINISH_CYCLE"

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return am.canScheduleExactAlarms()
    }

    fun schedule(context: Context, triggerAtMillis: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(context)
        try {
            if (canScheduleExact(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
        } catch (ignored: SecurityException) {
            // Exact-alarm permission revoked between the check and the call.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, FinishAlarmReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

class FinishAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != OffAlarm.ACTION) return
        AutomationScheduler.runFinishNow(context.applicationContext)
    }
}

/** Alarms are lost on reboot and app update; re-arm a pending OFF if there is one. */
class RearmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED ->
                AutomationScheduler.rearmPendingFinish(context.applicationContext)
        }
    }
}
