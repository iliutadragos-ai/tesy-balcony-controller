package ro.iliuta.tesybalcony

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.UUID
import java.util.Properties
import java.util.concurrent.TimeUnit

class TesyCloudException(message: String) : Exception(message)

data class TesyDevice(
    val mac: String,
    val name: String,
    val model: String,
    val modelType: String,
    val token: String,
    val currentTempC: Double?,
    val statusOn: Boolean?,
    val raw: JSONObject
)

/**
 * MyTESY cloud adapter based on the community-described v4 protocol.
 * It is intentionally isolated so the protocol can be changed after testing
 * against the exact CN 052 device. No credentials or device tokens are logged.
 */
class TesyCloudClient(private val credentials: TesyCredentials) {
    private val appId = UUID.randomUUID().toString().replace("-", "").take(16)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** Key names only (never values such as token, MAC or email), safe to paste when debugging. */
    var lastDiagnostics: String = ""
        private set

    suspend fun getDevices(): List<TesyDevice> = withContext(Dispatchers.IO) {
        val url = "https://ad.mytesy.com/rest/get-my-devices".toHttpUrl().newBuilder()
            .addQueryParameter("userID", credentials.userId)
            .addQueryParameter("userEmail", credentials.email)
            .addQueryParameter("userPass", credentials.password)
            .addQueryParameter("lang", "en")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Origin", "https://v4.mytesy.com")
            .header("Referer", "https://v4.mytesy.com/")
            .header("Accept", "application/json, text/plain, */*")
            .get()
            .build()
        val response = try { http.newCall(request).execute() }
        catch (ignored: Exception) { throw TesyCloudException("Nu pot contacta MyTESY. Verifică internetul.") }
        response.use { r ->
            if (r.code == 401 || r.code == 403) throw TesyCloudException("MyTESY a respins datele contului. Verifică emailul și parola (iar dacă ai completat user ID-ul, și acesta).")
            if (!r.isSuccessful) throw TesyCloudException("MyTESY a returnat eroarea HTTP ${r.code}.")
            val body = r.body?.string() ?: throw TesyCloudException("Răspuns MyTESY gol.")
            val root = try { JSONObject(body) } catch (ignored: Exception) { throw TesyCloudException("Răspuns MyTESY invalid.") }
            if (root.optString("error") == "1") throw TesyCloudException("Datele contului MyTESY nu au fost acceptate.")
            val devices = mutableListOf<TesyDevice>()
            val notes = mutableListOf<String>()
            fun names(o: JSONObject): String {
                val k = o.keys(); val l = mutableListOf<String>()
                while (k.hasNext()) l += k.next()
                return l.joinToString(",")
            }
            notes += "răspuns: " + names(root).take(200)
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val item = root.optJSONObject(key) ?: continue
                val state = item.optJSONObject("state")
                if (state == null) {
                    notes += "obiect fără state: " + names(item).take(200)
                    continue
                }
                val missing = listOf("status", "temp", "current_temp").filter { !state.has(it) }
                val type = (item.optString("type") + " " + item.optString("device_type")).lowercase()
                if (missing.isNotEmpty() || type.contains("boiler") || type.contains("water")) {
                    notes += "respins: lipsă=" + missing.joinToString(",") + " tip=" + type.trim() +
                        " model_type=" + item.optString("model_type") + " cheiItem=" + names(item).take(200) +
                        " cheiState=" + names(state).take(200)
                    continue
                }
                val mac = item.optString("mac", key).trim()
                val normalMac = normaliseMac(mac)
                if (normalMac.isBlank()) continue
                val model = item.optString("model")
                val token = item.optString("token")
                val temp = state.optString("current_temp").toDoubleOrNull()
                    ?.takeIf { it.isFinite() && it in -40.0..60.0 }
                val status = state.optString("status").lowercase()
                val on = when (status) {
                    "on", "1", "true" -> true
                    "off", "0", "false" -> false
                    else -> null
                }
                val name = item.optString("deviceName").takeIf { it.isNotBlank() }
                    ?: "TESY ${item.optString("model_type", model)}"
                devices += TesyDevice(mac, name, model, item.optString("model_type"), token, temp, on, item)
            }
            lastDiagnostics = notes.joinToString("\n").take(1500)
            devices
        }
    }

    suspend fun setPower(device: TesyDevice, on: Boolean) = withContext(Dispatchers.IO) {
        if (device.mac.isBlank() || device.model.isBlank() || device.token.isBlank() ||
            listOf(device.model, device.token).any { it.any { ch -> ch == '/' || ch == '#' || ch == '+' || ch == '\u0000' } }) {
            throw TesyCloudException("Convectorul nu a furnizat datele necesare controlului.")
        }
        val mqtt = MqttClient("wss://mqtt.tesy.com:8083/", "tesy_android_$appId", MemoryPersistence())
        try {
            val options = MqttConnectOptions().apply {
                userName = "client1"
                password = "123".toCharArray()
                isCleanSession = true
                mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
                connectionTimeout = 10
                keepAliveInterval = 20
                isAutomaticReconnect = false
                customWebSocketHeaders = Properties().apply {
                    setProperty("Origin", "https://v4.mytesy.com")
                    setProperty("Referer", "https://v4.mytesy.com/")
                }
            }
            mqtt.connect(options)
            val topic = "v1/${normaliseMac(device.mac)}/request/${device.model}/${device.token}/onOff"
            val payload = JSONObject().put("app_id", appId).put("status", if (on) "on" else "off")
            val message = MqttMessage(payload.toString().toByteArray(Charsets.UTF_8)).apply {
                qos = 0
                isRetained = false
            }
            mqtt.publish(topic, message)
        } catch (ignored: Exception) {
            throw TesyCloudException("Nu am putut trimite comanda către TESY.")
        } finally {
            try { if (mqtt.isConnected) mqtt.disconnect() } catch (ignored: Exception) { }
            try { mqtt.close() } catch (ignored: Exception) { }
        }
    }

    companion object {
        fun normaliseMac(value: String): String {
            val normal = value.replace(":", "").replace("-", "").uppercase()
            return normal.takeIf { it.matches(Regex("[0-9A-F]{12}")) } ?: ""
        }
    }
}
