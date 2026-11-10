package ro.iliuta.tesybalcony

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * Short foreground service started by the exact OFF alarm. It holds a wake lock and
 * retries the OFF for about two and a half minutes. If that fails it arms another
 * alarm, so a phone in Doze (no network until the next window) keeps getting chances.
 */
class FinishService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notifier.build(this, "TESY Balcony Controller", "Opresc convectorul…")
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notifier.FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            } else {
                startForeground(Notifier.FOREGROUND_ID, notification)
            }
        } catch (ignored: Exception) {
            // Not allowed to run as a foreground service right now: use the WorkManager path.
            AutomationScheduler.runFinishNow(applicationContext)
            stopSelf()
            return START_NOT_STICKY
        }
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tesybalcony:finish")
            ?.apply {
                setReferenceCounted(false)
                acquire(160_000L)
            }
        val context = applicationContext
        scope.launch {
            val end = System.currentTimeMillis() + 140_000L
            var attempt = 0
            var done = false
            while (!done && System.currentTimeMillis() < end) {
                done = CycleLock.mutex.withLock { CycleFinisher.run(context, early = false, attempt = attempt) }
                attempt++
                if (!done) delay(12_000L)
            }
            if (!done) {
                val settings = AutomationSettings(context)
                if (settings.nextFinishRetry() <= MAX_ALARM_RETRIES) {
                    OffAlarm.schedule(context, System.currentTimeMillis() + RETRY_DELAY_MS)
                    EventLog.add(context, "Oprirea nu a reușit încă. Nouă încercare peste 2 min.")
                } else {
                    EventLog.add(context, "Oprirea nu a reușit după mai multe încercări. Verifică convectorul în MyTESY.")
                    Notifier.show(context, "Atenție: oprire neconfirmată", "Verifică și oprește convectorul din MyTESY.")
                }
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private companion object {
        const val RETRY_DELAY_MS = 120_000L
        const val MAX_ALARM_RETRIES = 30
    }
}
