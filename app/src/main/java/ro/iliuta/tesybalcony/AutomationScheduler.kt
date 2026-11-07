package ro.iliuta.tesybalcony

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object AutomationScheduler {
    private const val PERIODIC_NAME = "tesy_balcony_temperature_check"
    private const val MANUAL_NAME = "tesy_balcony_manual_check"
    private const val FINISH_NAME = "tesy_balcony_finish_pending_cycle"
    private const val FINISH_ALARM_NAME = "tesy_balcony_finish_from_alarm"

    private fun networkConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun start(context: Context, intervalHours: Int) {
        val request = PeriodicWorkRequestBuilder<TesyCycleWorker>(
            intervalHours.coerceIn(1, 12).toLong(), TimeUnit.HOURS
        )
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
        )
        rearmPendingFinish(context)
    }

    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
        // The OFF task is deliberately not cancelled when future checks stop.
        rearmPendingFinish(context)
    }

    fun checkNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<TesyCycleWorker>()
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(MANUAL_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /** Arms the exact alarm for the OFF deadline plus a delayed WorkManager request as backup. */
    fun scheduleFinish(context: Context, delayMs: Long) {
        val delay = delayMs.coerceAtLeast(0L)
        OffAlarm.schedule(context, System.currentTimeMillis() + delay)
        val request = OneTimeWorkRequestBuilder<TesyFinishCycleWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            FINISH_NAME, ExistingWorkPolicy.KEEP, request
        )
    }

    /** Called by the alarm: run the OFF task now, with expedited priority. */
    fun runFinishNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<TesyFinishCycleWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            FINISH_ALARM_NAME, ExistingWorkPolicy.KEEP, request
        )
    }

    /** Clears the cycle marker and the pending alarm once OFF is confirmed. */
    fun finishCycle(context: Context) {
        AutomationSettings(context).clearCycle()
        OffAlarm.cancel(context)
    }

    fun rearmPendingFinish(context: Context) {
        val settings = AutomationSettings(context)
        val mac = settings.cycleMac
        val startedAt = settings.cycleStartedAt
        if (!mac.isNullOrBlank() && startedAt > 0L) {
            val deadline = startedAt + settings.cycleDurationMinutes * 60_000L
            scheduleFinish(context, deadline - System.currentTimeMillis())
        }
    }
}
