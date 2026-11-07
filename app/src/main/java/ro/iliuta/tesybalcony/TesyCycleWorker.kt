package ro.iliuta.tesybalcony

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock

/** Periodic/manual check. It never waits through a heating cycle; OFF is a separate persisted task. */
class TesyCycleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val settings = AutomationSettings(context)
    private val secure = SecureCredentials(context.getSharedPreferences("secure", Context.MODE_PRIVATE))

    override suspend fun doWork(): Result = CycleLock.mutex.withLock { check() }

    private fun log(message: String) = EventLog.add(applicationContext, message)

    private suspend fun check(): Result {
        val credentials = secure.load() ?: run {
            log("Verificare anulată: lipsesc datele contului MyTESY.")
            return Result.failure()
        }
        val activeMac = settings.cycleMac
        val activeStart = settings.cycleStartedAt
        if (!activeMac.isNullOrBlank() && activeStart > 0L) {
            // A previous ON may have succeeded before process death. Never start
            // a second cycle; make sure the OFF task and alarm exist.
            log("Verificare: un ciclu pornit de aplicație e încă în curs. Nu pornesc altul.")
            AutomationScheduler.rearmPendingFinish(applicationContext)
            return Result.success()
        }

        val selectedMac = settings.selectedMac ?: run {
            log("Verificare anulată: niciun convector selectat.")
            return Result.failure()
        }
        return try {
            val client = TesyCloudClient(credentials)
            val device = client.getDevices().firstOrNull {
                TesyCloudClient.normaliseMac(it.mac) == TesyCloudClient.normaliseMac(selectedMac)
            } ?: run {
                log("Convectorul selectat nu apare în contul MyTESY. Reîncerc.")
                return Result.retry()
            }
            val current = device.currentTempC ?: run {
                log("Temperatura nu poate fi citită. Nu pornesc. Reîncerc.")
                return Result.retry()
            }
            val temp = Fmt.temp(current)

            // Only a positively reported OFF state is eligible. Unknown status
            // is not treated as OFF, and a manually powered heater is untouched.
            if (!AutomationDecision.shouldStart(current, settings.thresholdC, device.statusOn)) {
                log(
                    when (device.statusOn) {
                        true -> "Verificare: $temp, convectorul e deja pornit. Nu intervin."
                        null -> "Verificare: $temp, starea pornit/oprit e necunoscută. Nu pornesc."
                        else -> "Verificare: $temp, nu e sub pragul de ${Fmt.temp(settings.thresholdC)}. Nimic de făcut."
                    }
                )
                return Result.success()
            }

            val duration = settings.durationMinutes
            val startedAt = System.currentTimeMillis()
            settings.markCycle(device.mac, startedAt, duration)
            log("Verificare: $temp, sub prag. Trimit pornire pentru $duration min.")
            client.setPower(device, true)

            if (waitForStatus(client, device.mac, expectedOn = true, requiredConsecutive = 2)) {
                val deadline = startedAt + settings.cycleDurationMinutes * 60_000L
                AutomationScheduler.scheduleFinish(applicationContext, deadline - System.currentTimeMillis())
                val exactNote = if (OffAlarm.canScheduleExact(applicationContext)) "" else " (aproximativ, lipsește permisiunea de alarmă exactă)"
                log("Pornire confirmată. Oprire programată la ${Fmt.clock(deadline)}$exactNote.")
                Notifier.show(applicationContext, "Convector pornit", "$temp. Se oprește la ${Fmt.clock(deadline)}.")
                Result.success()
            } else {
                // A cloud publish is not proof the heater accepted ON. Send OFF
                // defensively and require repeated read-back before clearing the
                // cycle marker. Never retry ON in this same attempt.
                log("Pornirea nu a fost confirmată de MyTESY. Trimit oprire de siguranță.")
                val latest = runCatching {
                    client.getDevices().firstOrNull {
                        TesyCloudClient.normaliseMac(it.mac) == TesyCloudClient.normaliseMac(device.mac)
                    }
                }.getOrNull()
                client.setPower(latest ?: device, false)
                if (waitForStatus(client, device.mac, expectedOn = false, requiredConsecutive = 2)) {
                    AutomationScheduler.finishCycle(applicationContext)
                    log("Oprirea de siguranță a fost confirmată.")
                    Notifier.show(applicationContext, "Pornire neconfirmată", "MyTESY nu a confirmat pornirea, așa că am oprit convectorul. Verifică jurnalul.")
                    Result.success()
                } else {
                    AutomationScheduler.scheduleFinish(applicationContext, 0L)
                    log("Nici oprirea nu a putut fi confirmată. Reîncerc oprirea.")
                    Notifier.show(applicationContext, "Atenție: stare neconfirmată", "Nu pot confirma starea convectorului. Verifică-l în MyTESY.")
                    Result.retry()
                }
            }
        } catch (e: TesyCloudException) {
            log("Eroare la verificare: ${e.message} Reîncerc.")
            AutomationScheduler.rearmPendingFinish(applicationContext)
            Result.retry()
        } catch (ignored: Exception) {
            log("Eroare neașteptată la verificare. Reîncerc.")
            AutomationScheduler.rearmPendingFinish(applicationContext)
            Result.retry()
        }
    }

    private suspend fun waitForStatus(
        client: TesyCloudClient,
        mac: String,
        expectedOn: Boolean,
        requiredConsecutive: Int
    ): Boolean {
        var consecutive = 0
        val seen = mutableListOf<String>()
        // MyTESY can take well over 10 s to report the new state, so wait up to ~40 s.
        repeat(10) { attempt ->
            delay(if (attempt == 0) 3_000L else 4_000L)
            val latest = runCatching { client.getDevices() }.getOrNull()
            val found = latest?.firstOrNull {
                TesyCloudClient.normaliseMac(it.mac) == TesyCloudClient.normaliseMac(mac)
            }
            seen += if (latest == null) "eroare" else if (found == null) "lipsă"
                else found.raw.optJSONObject("state")?.optString("status")?.take(12).orEmpty().ifBlank { "?" }
            if (found?.statusOn == expectedOn) {
                consecutive++
                if (consecutive >= requiredConsecutive) {
                    log("Stări citite (${if (expectedOn) "pornire" else "oprire"}): ${seen.joinToString(", ")}")
                    return true
                }
            } else {
                consecutive = 0
            }
        }
        log("Stări citite (${if (expectedOn) "pornire" else "oprire"}): ${seen.joinToString(", ")}")
        return false
    }
}
