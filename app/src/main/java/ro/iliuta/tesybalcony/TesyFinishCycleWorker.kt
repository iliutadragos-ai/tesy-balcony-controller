package ro.iliuta.tesybalcony

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock

/**
 * OFF task for an app-owned cycle. Started on time by [OffAlarm] (expedited),
 * with a delayed WorkManager copy as backup. Survives stopping periodic checks
 * and process death.
 */
class TesyFinishCycleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val settings = AutomationSettings(context)
    private val secure = SecureCredentials(context.getSharedPreferences("secure", Context.MODE_PRIVATE))

    override suspend fun doWork(): Result = CycleLock.mutex.withLock { finish() }

    /** Needed for expedited work on Android 11 and older, where it runs as a foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(
            Notifier.FOREGROUND_ID,
            Notifier.build(applicationContext, "TESY Balcony Controller", "Opresc convectorul…")
        )

    private fun log(message: String) = EventLog.add(applicationContext, message)

    private suspend fun finish(): Result {
        val mac = settings.cycleMac
        val startedAt = settings.cycleStartedAt
        if (mac.isNullOrBlank() || startedAt <= 0L) return Result.success()

        val deadline = startedAt + settings.cycleDurationMinutes * 60_000L
        val early = inputData.getBoolean(AutomationScheduler.KEY_EARLY, false)
        if (!early && deadline - System.currentTimeMillis() > EARLY_TOLERANCE_MS) {
            // Too early (e.g. the wall clock moved). Re-arm the alarm and try later.
            OffAlarm.schedule(applicationContext, deadline)
            return Result.retry()
        }

        val credentials = secure.load() ?: run {
            log("Oprire: nu pot citi datele contului. Reîncerc.")
            warnIfRepeated()
            return Result.retry()
        }
        return try {
            val client = TesyCloudClient(credentials)
            val device = client.getDevices().firstOrNull {
                TesyCloudClient.normaliseMac(it.mac) == TesyCloudClient.normaliseMac(mac)
            } ?: run {
                log("Oprire: convectorul nu apare în contul MyTESY. Reîncerc.")
                warnIfRepeated()
                return Result.retry()
            }

            if (device.statusOn == false) {
                AutomationScheduler.finishCycle(applicationContext)
                log("Oprire: convectorul era deja oprit.")
                Result.success()
            } else {
                // ON or unknown: the persisted marker proves this app started the
                // cycle, so an idempotent OFF is sent and then read back.
                client.setPower(device, false)
                if (waitForOff(client, mac)) {
                    AutomationScheduler.finishCycle(applicationContext)
                    val minutes = (System.currentTimeMillis() - startedAt) / 60_000L
                    log("Oprire confirmată după $minutes min.")
                    Notifier.show(applicationContext, "Convector oprit", "A funcționat $minutes minute.")
                    Result.success()
                } else {
                    log("Oprirea nu a fost confirmată de MyTESY. Reîncerc.")
                    warnIfRepeated()
                    Result.retry()
                }
            }
        } catch (e: TesyCloudException) {
            log("Oprire: ${e.message} Reîncerc.")
            warnIfRepeated()
            Result.retry()
        } catch (ignored: Exception) {
            log("Oprire: eroare neașteptată. Reîncerc.")
            warnIfRepeated()
            Result.retry()
        }
    }

    /** One notification on the third failed attempt, so retries do not spam. */
    private fun warnIfRepeated() {
        if (runAttemptCount == 2) {
            Notifier.show(
                applicationContext,
                "Atenție: oprire neconfirmată",
                "Nu pot confirma oprirea convectorului. Verifică-l în MyTESY."
            )
        }
    }

    private suspend fun waitForOff(client: TesyCloudClient, mac: String): Boolean {
        var consecutiveOff = 0
        repeat(5) { attempt ->
            delay(if (attempt == 0) 2_000L else 3_000L)
            val devices = runCatching { client.getDevices() }.getOrNull()
            val state = devices?.firstOrNull {
                TesyCloudClient.normaliseMac(it.mac) == TesyCloudClient.normaliseMac(mac)
            }?.statusOn
            if (state == false) {
                consecutiveOff++
                if (consecutiveOff >= 2) return true
            } else {
                consecutiveOff = 0
            }
        }
        return false
    }

    private companion object {
        const val EARLY_TOLERANCE_MS = 15_000L
    }
}
