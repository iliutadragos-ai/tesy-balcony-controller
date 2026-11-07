package ro.iliuta.tesybalcony

import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var secure: SecureCredentials
    private lateinit var settings: AutomationSettings
    private lateinit var statusView: TextView
    private var scrollView: ScrollView? = null
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var userIdInput: EditText
    private lateinit var thresholdInput: EditText
    private lateinit var durationInput: EditText
    private lateinit var intervalInput: EditText
    private lateinit var deviceSpinner: Spinner
    private lateinit var permissionsView: TextView
    private lateinit var logView: TextView
    private var discovered = emptyList<TesyDevice>()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secure = SecureCredentials(getSharedPreferences("secure", Context.MODE_PRIVATE))
        settings = AutomationSettings(this)
        buildUi()
        loadSavedValues()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        refreshLog()
        // If the exact-alarm permission was just granted, re-arm a pending OFF with it.
        AutomationScheduler.rearmPendingFinish(this)
    }

    private fun refreshPermissions() {
        if (!::permissionsView.isInitialized) return
        val exact = OffAlarm.canScheduleExact(this)
        val battery = getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true
        val notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        permissionsView.text = listOf(
            (if (exact) "✓" else "✗") + " Oprire la minut exact" + (if (exact) "" else " (fără ea, oprirea poate întârzia câteva minute)"),
            (if (battery) "✓" else "✗") + " Exceptată de la economisirea bateriei" + (if (battery) "" else " (fără ea, verificările pot întârzia ore)"),
            (if (notifications) "✓" else "✗") + " Notificări"
        ).joinToString("\n")
    }

    private fun refreshLog() {
        if (!::logView.isInitialized) return
        val entries = EventLog.recent(this, 25)
        logView.text = if (entries.isEmpty()) "Nicio activitate încă." else entries.joinToString("\n")
    }

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action, Uri.parse("package:$packageName")))
        } catch (ignored: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
            setBackgroundColor(0xFFF7F5F8.toInt())
        }
        val scroll = ScrollView(this).apply { addView(root) }
        scrollView = scroll
        setContentView(scroll)

        val title = TextView(this).apply {
            text = "TESY Balcony Controller"
            textSize = 25f
            setTextColor(0xFF4A1450.toInt())
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(title, matchWrap())
        addText(root, "Control automat pentru convectorul de pe balcon", 14f, 0xFF55515A.toInt(), top = 4)
        addText(root, "Setări inițiale: prag 10°C · pornire 20 min · verificare la 3 ore", 14f, 0xFF4A1450.toInt(), top = 14)

        section(root, "1. Conectare MyTESY")
        emailInput = field(root, "Email MyTESY", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        passwordInput = field(root, "Parolă MyTESY", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        userIdInput = field(root, "MyTESY user ID (opțional)", InputType.TYPE_CLASS_TEXT)
        addText(root, "User ID-ul este opțional: lasă-l gol dacă nu-l ai. Încearcă mai întâi doar cu email și parolă. Dacă MyTESY îl cere, e un număr din contul tău (nu parola și nu emailul).", 12f, 0xFF6B6670.toInt(), top = 3)

        button(root, "Testează conexiunea și caută convectorul") { testConnection() }
        deviceSpinner = Spinner(this)
        root.addView(deviceSpinner, matchWrap().apply { topMargin = dp(8) })

        section(root, "2. Parametrii automatizării")
        thresholdInput = field(root, "Prag temperatură (°C)", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        durationInput = field(root, "Durată pornire (minute)", InputType.TYPE_CLASS_NUMBER)
        intervalInput = field(root, "Verificare la fiecare (ore)", InputType.TYPE_CLASS_NUMBER)
        button(root, "Salvează configurația") { saveAll(showMessage = true) }

        section(root, "3. Automatizare")
        button(root, "ACTIVEAZĂ automatizarea") {
            if (!saveAll(showMessage = false)) return@button
            if (settings.selectedMac.isNullOrBlank()) {
                showStatus("Mai întâi testează conexiunea și selectează convectorul.")
            } else {
                AutomationScheduler.start(this, settings.intervalHours)
                AutomationScheduler.checkNow(this)
                EventLog.add(this, "Automatizare activată: prag ${settings.thresholdC}°C, ${settings.durationMinutes} min, la fiecare ${settings.intervalHours} ore.")
                refreshLog()
                showStatus("Automatizare activă. Prima verificare a fost solicitată acum; următoarele vor fi programate la fiecare ${settings.intervalHours} ore.")
            }
        }
        button(root, "Oprește automatizarea", secondary = true) {
            AutomationScheduler.stop(this)
            EventLog.add(this, "Automatizare oprită de utilizator.")
            refreshLog()
            showStatus("Verificările viitoare au fost oprite. Un ciclu de încălzire deja început își va executa oprirea de siguranță.")
        }
        button(root, "Verifică acum", secondary = true) {
            if (saveAll(showMessage = false)) {
                AutomationScheduler.checkNow(this)
                showStatus("Verificare solicitată. Poate fi amânată de Android dacă telefonul este în economisire agresivă a bateriei.")
            }
        }

        addText(root, "Aplicația doar pornește și oprește convectorul. Încălzește până la temperatura setată pe aparat (Comfort/Eco), deci setează-o înainte la valoarea dorită.", 12f, 0xFF6B6670.toInt(), top = 10)

        section(root, "4. Permisiuni pentru funcționare la timp")
        permissionsView = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF333039.toInt())
            setLineSpacing(0f, 1.25f)
        }
        root.addView(permissionsView, matchWrap().apply { topMargin = dp(6) })
        button(root, "Permite oprirea la minut exact", secondary = true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                openSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            } else {
                showStatus("Pe această versiune de Android oprirea la minut exact e permisă implicit.")
            }
        }
        button(root, "Scoate aplicația din economisirea bateriei", secondary = true) {
            openSettings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        }
        addText(root, "Pe OnePlus verifică și: Setări → Aplicații → TESY Balcony Controller → Utilizare baterie → Nerestricționat.", 12f, 0xFF6B6670.toInt(), top = 6)

        section(root, "Status")
        statusView = TextView(this).apply {
            text = "Neconfigurat. Introdu datele MyTESY și testează conexiunea."
            textSize = 14f
            setTextColor(0xFF333039.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        root.addView(statusView, matchWrap())

        section(root, "Istoric")
        logView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF333039.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        root.addView(logView, matchWrap().apply { topMargin = dp(6) })
        button(root, "Reîmprospătează istoricul", secondary = true) { refreshLog() }
        addText(root, "Important: dacă temperatura nu poate fi citită, aplicația nu pornește încălzirea. Dacă aparatul era deja pornit manual, aplicația nu îl preia și nu îl oprește. Confirmarea se face recitind starea din MyTESY.", 12f, 0xFF6B6670.toInt(), top = 14)
        addText(root, "Aplicație neoficială, independentă de TESY. Necesită internet și cont MyTESY.", 11f, 0xFF817B85.toInt(), top = 12)
    }

    private fun loadSavedValues() {
        secure.load()?.let {
            emailInput.setText(it.email)
            passwordInput.setText(it.password)
            userIdInput.setText(it.userId)
        }
        thresholdInput.setText(settings.thresholdC.toString())
        durationInput.setText(settings.durationMinutes.toString())
        intervalInput.setText(settings.intervalHours.toString())
        val savedMac = settings.selectedMac
        if (!savedMac.isNullOrBlank()) showStatus("Convector memorat: ${savedMac}. Apasă «Testează conexiunea» pentru a verifica starea curentă.")
    }

    private fun testConnection() {
        if (!saveAll(showMessage = false)) return
        val credentials = secure.load() ?: return
        showStatus("Conectare la MyTESY și descoperire dispozitive…")
        lifecycleScope.launch {
            try {
                val devices = TesyCloudClient(credentials).getDevices()
                discovered = devices
                if (devices.isEmpty()) {
                    showStatus("Contul a răspuns, dar nu am găsit un convector compatibil. Nu trimitem comenzi. Verificăm modelul și protocolul.")
                    return@launch
                }
                val labels = devices.map {
                    "${it.name} — ${it.modelType.ifBlank { it.model }} — ${it.currentTempC?.let { t -> String.format(Locale.US, "%.1f°C", t) } ?: "temperatură indisponibilă"}"
                }
                deviceSpinner.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels)
                val wanted = settings.selectedMac?.let { TesyCloudClient.normaliseMac(it) }
                val index = devices.indexOfFirst { TesyCloudClient.normaliseMac(it.mac) == wanted }.let { if (it >= 0) it else 0 }
                deviceSpinner.setSelection(index)
                settings.selectedMac = devices[index].mac
                showStatus("Conectare reușită. Găsite ${devices.size} dispozitiv(e). Selectat: ${devices[index].name}, ${devices[index].modelType.ifBlank { devices[index].model }}, ${devices[index].currentTempC ?: "?"}°C. Compatibilitatea CN 052 trebuie confirmată prin acest test pe aparatul tău.")
            } catch (e: Exception) {
                showStatus(e.message ?: "Nu am putut verifica MyTESY.")
            }
        }
    }

    private fun saveAll(showMessage: Boolean): Boolean {
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()
        val userId = userIdInput.text.toString().trim()
        if (email.isBlank() || password.isBlank()) {
            showStatus("Completează emailul și parola MyTESY. Nu trimite aceste date în conversație.")
            return false
        }
        val threshold = thresholdInput.text.toString().replace(',', '.').toDoubleOrNull()
        val duration = durationInput.text.toString().toIntOrNull()
        val interval = intervalInput.text.toString().toIntOrNull()
        if (threshold == null || !threshold.isFinite() || threshold !in 0.0..30.0 || duration == null || duration !in 5..60 || interval == null || interval !in 1..12) {
            showStatus("Setări invalide. Prag: 0–30°C; durată: 5–60 minute; interval: 1–12 ore.")
            return false
        }
        secure.save(TesyCredentials(email, password, userId))
        settings.thresholdC = threshold
        settings.durationMinutes = duration
        settings.intervalHours = interval
        if (discovered.isNotEmpty()) {
            val selected = discovered.getOrNull(deviceSpinner.selectedItemPosition)
            if (selected != null) settings.selectedMac = selected.mac
        }
        if (showMessage) showStatus("Configurația a fost salvată pe telefon. Datele contului sunt criptate cu Android Keystore.")
        return true
    }

    private fun field(parent: LinearLayout, label: String, type: Int): EditText {
        addText(parent, label, 13f, 0xFF45414A.toInt(), top = 10)
        return EditText(this).apply {
            inputType = type
            textSize = 16f
            isSingleLine = true
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xFFFFFFFF.toInt())
            parent.addView(this, matchWrap().apply { topMargin = dp(4) })
        }
    }

    private fun section(parent: LinearLayout, title: String) {
        addText(parent, title, 18f, 0xFF4A1450.toInt(), top = 22, bold = true)
    }

    private fun addText(parent: LinearLayout, value: String, size: Float, color: Int, top: Int = 0, bold: Boolean = false) {
        val view = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        parent.addView(view, matchWrap().apply { topMargin = dp(top) })
    }

    private fun button(parent: LinearLayout, label: String, secondary: Boolean = false, action: () -> Unit) {
        val b = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(if (secondary) 0xFF5A155F.toInt() else 0xFFFFFFFF.toInt())
            setBackgroundColor(if (secondary) 0xFFEDE6F1.toInt() else 0xFF6E0B72.toInt())
            setOnClickListener { action() }
        }
        parent.addView(b, matchWrap().apply { topMargin = dp(10) })
    }

    private fun showStatus(value: String) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show()
        if (::statusView.isInitialized) {
            statusView.text = value
            // The status box is far below the buttons; bring it into view.
            scrollView?.post { scrollView?.smoothScrollTo(0, statusView.top) }
        }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
