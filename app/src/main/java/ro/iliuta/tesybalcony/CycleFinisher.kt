package ro.iliuta.tesybalcony

import android.content.Context
import kotlinx.coroutines.delay

/**
 * The OFF routine for an app-owned heating cycle, shared by the foreground
 * service started from the alarm and by the WorkManager backup.
 * Returns true when there is nothing left to do (heater confirmed OFF, or no cycle),
 * false when it should be retried. Callers hold [CycleLock].
 */
object CycleFinisher {
    private const val EARLY_TOLERANCE_MS = 15_000L

    suspend fun run(context: Context, early: Boolean, attempt: Int): Boolean {
        val settings = AutomationSettings(context)
        val secure = SecureCredentials(context.getSharedPreferences("secure", Context.MODE_PRIVATE))
        fun log(message: String) = EventLog.add(context, message)
        fun warn() {
            if (attempt == 2) {
                Notifier.show(context, "Atenție: oprire neconfirmată", "Nu pot confirma oprirea convectorului. Verifică-l în MyTESY.")
            }
        }

        val mac = settings.cycleMac
        val startedAt = settings.cycleStartedAt
        if (mac.isNullOrBlank() || startedAt <= 0L) return true

        val deadline = startedAt + settings.cycleDurationMinutes * 60_000L
        if (!early && deadline - System.currentTimeMillis() > EARLY_TOLERANCE_MS) {
            // Too early (e.g. the wall clock moved). Re-arm the alarm and try later.
            OffAlarm.schedule(context, deadline)
            return false
        }

        val credentials = secure.load() ?: run {
            log("Oprire: nu pot citi datele contului. Reîncerc.")
            warn()
            return false
        }
        return try {
            val client = TesyCloudClient(credentials)
            val device = client.getDevices().firstOrNull {
                TesyCloudClient.normaliseMac(it.mac) == TesyCloudClient.normaliseMac(mac)
            } ?: run {
                log("Oprire: convectorul nu apare în contul MyTESY. Reîncerc.")
                warn()
                return false
            }

            if (device.statusOn == false) {
                AutomationScheduler.finishCycle(context)
                log("Oprire: convectorul era deja oprit.")
                true
            } else {
                // ON or unknown: the persisted marker proves this app started the
                // cycle, so an idempotent OFF is sent and then read back.
                client.setPower(device, false)
                if (waitForOff(client, mac)) {
                    AutomationScheduler.finishCycle(context)
                    val minutes = (System.currentTimeMillis() - startedAt) / 60_000L
                    log("Oprire confirmată după $minutes min.")
                    Notifier.show(context, "Convector oprit", "A funcționat $minutes minute.")
                    true
                } else {
                    log("Oprirea nu a fost confirmată de MyTESY. Reîncerc.")
                    warn()
                    false
                }
            }
        } catch (e: TesyCloudException) {
            log("Oprire: ${e.message} Reîncerc.")
            warn()
            false
        } catch (ignored: Exception) {
            log("Oprire: eroare neașteptată. Reîncerc.")
            warn()
            false
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
}
