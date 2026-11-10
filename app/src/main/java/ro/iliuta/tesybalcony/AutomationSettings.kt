package ro.iliuta.tesybalcony

import android.content.Context

class AutomationSettings(context: Context) {
    private val prefs = context.getSharedPreferences("automation", Context.MODE_PRIVATE)

    /** The heater is stopped early once the room reaches thresholdC + hysteresisC. */
    var hysteresisC: Double
        get() = prefs.getString("hysteresis_c", null)?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..10.0 } ?: 2.0
        set(value) { prefs.edit().putString("hysteresis_c", value.toString()).apply() }

    val stopTargetC: Double get() = thresholdC + hysteresisC

    var thresholdC: Double
        get() = prefs.getString("threshold", "10")?.toDoubleOrNull() ?: 10.0
        set(value) { prefs.edit().putString("threshold", value.toString()).apply() }

    var durationMinutes: Int
        get() = prefs.getInt("duration", 20)
        set(value) { prefs.edit().putInt("duration", value.coerceIn(5, 60)).apply() }

    var intervalHours: Int
        get() = prefs.getInt("interval", 3)
        set(value) { prefs.edit().putInt("interval", value.coerceIn(1, 12)).apply() }

    var selectedMac: String?
        get() = prefs.getString("selected_mac", null)
        set(value) { prefs.edit().putString("selected_mac", value).apply() }

    val hasAutomationFlag: Boolean get() = prefs.contains("automation_enabled")

    var automationEnabled: Boolean
        get() = prefs.getBoolean("automation_enabled", false)
        set(value) { prefs.edit().putBoolean("automation_enabled", value).apply() }

    var deviceName: String?
        get() = prefs.getString("device_name", null)
        set(value) { prefs.edit().putString("device_name", value).apply() }

    var lastTempC: Double?
        get() = prefs.getString("last_temp", null)?.toDoubleOrNull()
        set(value) { prefs.edit().putString("last_temp", value?.toString()).apply() }

    var lastTempAt: Long
        get() = prefs.getLong("last_temp_at", 0L)
        set(value) { prefs.edit().putLong("last_temp_at", value).apply() }

    /** Counts alarm-driven OFF retries for the current cycle (resets when a new cycle starts). */
    fun nextFinishRetry(): Int {
        val cycle = cycleStartedAt
        if (prefs.getLong("finish_retry_cycle", -1L) != cycle) {
            prefs.edit().putLong("finish_retry_cycle", cycle).putInt("finish_retries", 0).apply()
        }
        val next = prefs.getInt("finish_retries", 0) + 1
        prefs.edit().putInt("finish_retries", next).apply()
        return next
    }

    var cycleMac: String?
        get() = prefs.getString("cycle_mac", null)
        private set(value) { prefs.edit().putString("cycle_mac", value).apply() }

    var cycleStartedAt: Long
        get() = prefs.getLong("cycle_started_at", 0L)
        private set(value) { prefs.edit().putLong("cycle_started_at", value).apply() }

    var cycleDurationMinutes: Int
        get() = prefs.getInt("cycle_duration", 20).coerceIn(5, 60)
        private set(value) { prefs.edit().putInt("cycle_duration", value.coerceIn(5, 60)).apply() }

    @Synchronized fun markCycle(mac: String, startedAt: Long, durationMinutes: Int) {
        cycleMac = mac
        cycleStartedAt = startedAt
        cycleDurationMinutes = durationMinutes
    }

    @Synchronized fun clearCycle() {
        cycleMac = null
        cycleStartedAt = 0L
        cycleDurationMinutes = 20
    }
}

object AutomationDecision {
    /** Unknown temperature or power state must never trigger heating. */
    fun shouldStart(currentTempC: Double?, thresholdC: Double, isOn: Boolean?): Boolean {
        if (currentTempC == null || !currentTempC.isFinite() || isOn != false) return false
        if (!thresholdC.isFinite()) return false
        return currentTempC < thresholdC
    }
}
