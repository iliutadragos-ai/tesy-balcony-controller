package ro.iliuta.tesybalcony

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Serialises temperature checks and OFF tasks so two workers never act at the same time. */
object CycleLock {
    val mutex = Mutex()
}

/** Small on-device history of what the automation did. Newest entry first. No secrets are logged. */
object EventLog {
    private const val PREFS = "event_log"
    private const val KEY = "entries"
    private const val MAX = 80

    @Synchronized
    fun add(context: Context, message: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = try { JSONArray(prefs.getString(KEY, "[]")) } catch (ignored: Exception) { JSONArray() }
        val out = JSONArray()
        out.put(JSONObject().put("t", System.currentTimeMillis()).put("m", message))
        for (i in 0 until minOf(old.length(), MAX - 1)) {
            old.optJSONObject(i)?.let { out.put(it) }
        }
        prefs.edit().putString(KEY, out.toString()).commit()
    }

    @Synchronized
    fun recent(context: Context, limit: Int): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = try { JSONArray(prefs.getString(KEY, "[]")) } catch (ignored: Exception) { JSONArray() }
        val format = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        val result = mutableListOf<String>()
        for (i in 0 until minOf(arr.length(), limit)) {
            val item = arr.optJSONObject(i) ?: continue
            result += format.format(Date(item.optLong("t"))) + "  " + item.optString("m")
        }
        return result
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

object Notifier {
    private const val CHANNEL = "tesy_cycles"
    const val FOREGROUND_ID = 1001
    private const val EVENT_ID = 1002

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Cicluri de încălzire", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    fun build(context: Context, title: String, text: String): Notification {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
    }

    @SuppressLint("MissingPermission")
    fun show(context: Context, title: String, text: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(EVENT_ID, build(context, title, text))
        } catch (ignored: SecurityException) {
            // Notification permission not granted; the event log still has the entry.
        }
    }
}

object Fmt {
    fun temp(value: Double): String = String.format(Locale.US, "%.1f°C", value)
    fun clock(millis: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))
}
