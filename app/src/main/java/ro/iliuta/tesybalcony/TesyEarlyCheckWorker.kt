package ro.iliuta.tesybalcony

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.sync.withLock

/**
 * Mid-cycle check, every 5 minutes while an app-owned heating cycle runs.
 * If the room reached the temperature set on the heater, the heater is turned off
 * early (through the same confirmed OFF path). It never sends ON and never changes
 * the end-of-cycle alarm, which stays as the guaranteed stop.
 */
class TesyEarlyCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val settings = AutomationSettings(context)
    private val secure = SecureCredentials(context.getSharedPreferences("secure", Context.MODE_PRIVATE))

    override suspend fun doWork(): Result = CycleLock.mutex.withLock { check() }

    private fun log(message: String) = EventLog.add(applicationContext, message)

    private suspend fun check(): Result {
        val mac = settings.cycleMac
        val startedAt = settings.cycleStartedAt
        if (mac.isNullOrBlank() || startedAt <= 0L) return Result.success()
        val now = System.currentTimeMillis()
        val deadline = startedAt + settings.cycleDurationMinutes * 60_000L
        // The final OFF handles the last minute.
        if (deadline - now < 60_000L) return Result.success()

        val credentials = secure.load()
        if (credentials == null) {
            AutomationScheduler.scheduleNextEarlyCheck(applicationContext)
            return Result.success()
        }
        try {
            val device = TesyCloudClient(credentials).getDevices().firstOrNull {
                TesyCloudClient.normaliseMac(it.mac) == TesyCloudClient.normaliseMac(mac)
            }
            val current = device?.currentTempC
            val target = device?.raw?.optJSONObject("state")?.optString("temp")?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it in 0.0..60.0 }
            val minutes = (now - startedAt) / 60_000L
            val stopTarget = settings.stopTargetC
            if (device != null && current != null) {
                val reachedStop = current >= stopTarget
                val reachedDevice = target != null && current >= target
                if (device.statusOn != false && (reachedStop || reachedDevice)) {
                    val why = if (reachedStop) "a atins ${Fmt.temp(stopTarget)} (prag + histerezis)"
                        else "a atins ${Fmt.temp(target!!)} setat pe aparat"
                    log("Verificare la $minutes min: ${Fmt.temp(current)}, $why. Opresc.")
                    AutomationScheduler.runEarlyStopNow(applicationContext)
                    return Result.success()
                }
                log("Verificare la $minutes min: ${Fmt.temp(current)}, oprire la ${Fmt.temp(stopTarget)}. Continui.")
            } else {
                log("Verificare la $minutes min: temperatura nu poate fi citită. Continui până la oprirea programată.")
            }
        } catch (ignored: Exception) {
            // The guaranteed OFF at the deadline is unaffected; just try the next slot.
        }
        AutomationScheduler.scheduleNextEarlyCheck(applicationContext)
        return Result.success()
    }
}
