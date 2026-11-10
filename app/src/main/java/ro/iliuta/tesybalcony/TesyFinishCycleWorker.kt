package ro.iliuta.tesybalcony

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.sync.withLock

/**
 * WorkManager copy of the OFF task (delayed backup and early stop). The primary
 * path at the deadline is [FinishService], started by the exact alarm.
 */
class TesyFinishCycleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = CycleLock.mutex.withLock {
        val early = inputData.getBoolean(AutomationScheduler.KEY_EARLY, false)
        if (CycleFinisher.run(applicationContext, early, runAttemptCount)) Result.success() else Result.retry()
    }

    /** Needed for expedited work on Android 11 and older, where it runs as a foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(
            Notifier.FOREGROUND_ID,
            Notifier.build(applicationContext, "TESY Balcony Controller", "Opresc convectorul…")
        )
}
