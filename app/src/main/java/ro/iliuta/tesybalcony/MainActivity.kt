package ro.iliuta.tesybalcony

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BRAND = 0xFF6E0B72.toInt()
private val INK = 0xFF26222B.toInt()
private val MUTED = 0xFF6B6670.toInt()
private val WHITE = 0xFFFFFFFF.toInt()
private val OK_FG = 0xFF1B5E20.toInt()
private val OK_BG = 0xFFE3F3E5.toInt()
private val WARN_FG = 0xFF8A5300.toInt()
private val WARN_BG = 0xFFFFF1D6.toInt()
private val ERR_FG = 0xFFB3261E.toInt()
private val ERR_BG = 0xFFFCE8E6.toInt()
private val INFO_FG = 0xFF3B3F8F.toInt()
private val INFO_BG = 0xFFEAEBFA.toInt()
private val NEUTRAL_BG = 0xFFEFEBF2.toInt()

private const val TONE_INFO = 0
private const val TONE_OK = 1
private const val TONE_WARN = 2
private const val TONE_ERROR = 3

private enum class BtnStyle { PRIMARY, SECONDARY, TEXT }

private class PermRow(val badge: TextView, val action: TextView)

class MainActivity : ComponentActivity() {
    private lateinit var secure: SecureCredentials
    private lateinit var settings: AutomationSettings
    private var scrollView: ScrollView? = null

    private lateinit var heroName: TextView
    private lateinit var heroTemp: TextView
    private lateinit var heroLine: TextView
    private lateinit var heroChip: TextView
    private lateinit var banner: TextView

    private lateinit var accountChip: TextView
    private lateinit var accountForm: LinearLayout
    private lateinit var accountSummary: LinearLayout
    private lateinit var accountSummaryText: TextView
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var userIdInput: EditText
    private lateinit var connectButton: Button
    private lateinit var deviceSpinner: Spinner

    private lateinit var automationCard: LinearLayout
    private lateinit var automationChip: TextView
    private lateinit var thresholdInput: EditText
    private lateinit var hysteresisInput: EditText
    private lateinit var targetSwitch: android.widget.Switch
    private lateinit var automationSummary: TextView
    private lateinit var durationInput: EditText
    private lateinit var intervalInput: EditText
    private lateinit var toggleButton: Button

    private lateinit var permissionsCard: LinearLayout
    private lateinit var permissionsChip: TextView
    private lateinit var rowExact: PermRow
    private lateinit var rowBattery: PermRow
    private lateinit var rowNotif: PermRow

    private lateinit var logView: TextView

    private var discovered = emptyList<TesyDevice>()
    private var connectionOk: Boolean? = null
    private var editingAccount = false
    private var passwordVisible = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secure = SecureCredentials(getSharedPreferences("secure", Context.MODE_PRIVATE))
        settings = AutomationSettings(this)
        buildUi()
        loadSavedValues()
        syncAutomationFlag()
        applyState()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Already set up: verify quietly in the background so the top card shows the live temperature.
        if (isConfigured()) testConnection(silent = true)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
        refreshLog()
        if (::heroTemp.isInitialized) updateHero()
        // If the exact-alarm permission was just granted, re-arm a pending OFF with it.
        AutomationScheduler.rearmPendingFinish(this)
    }

    // ---------- UI building ----------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(28))
        }
        val scroll = ScrollView(this).apply {
            addView(root)
            clipToPadding = false
        }
        scrollView = scroll
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        buildHeader(root)
        buildHero(root)

        banner = TextView(this).apply {
            textSize = 14f
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setLineSpacing(0f, 1.15f)
            visibility = View.GONE
            setOnClickListener { visibility = View.GONE }
        }
        root.addView(banner, matchWrap().apply { topMargin = dp(12) })

        buildAccountCard(root)
        buildAutomationCard(root)
        buildPermissionsCard(root)
        buildLogCard(root)

        addText(root, "Dacă temperatura nu poate fi citită, aplicația nu pornește încălzirea. Dacă aparatul era deja pornit manual, aplicația nu îl preia și nu îl oprește. Confirmarea se face recitind starea din MyTESY.", 12f, MUTED, top = 18)
        addText(root, "Aplicație neoficială, independentă de TESY. Necesită internet și cont MyTESY. v${versionName()}", 11f, 0xFF8A858E.toInt(), top = 10)
    }

    private fun buildHeader(root: LinearLayout) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }
        row.addView(icon, LinearLayout.LayoutParams(dp(52), dp(52)))
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        titles.addView(TextView(this).apply {
            text = "Balcony Controller"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(BRAND)
        })
        titles.addView(TextView(this).apply {
            text = "Convector TESY, pornit automat când e frig"
            textSize = 13f
            setTextColor(MUTED)
        })
        row.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row, matchWrap())
    }

    private fun buildHero(root: LinearLayout) {
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF5E0961.toInt(), 0xFF9A2FA0.toInt())
            ).apply { cornerRadius = dp(22).toFloat() }
            elevation = dp(2).toFloat()
        }
        heroName = TextView(this).apply {
            textSize = 14f
            setTextColor(0xCCFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
        }
        heroTemp = TextView(this).apply {
            textSize = 46f
            setTextColor(WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        heroLine = TextView(this).apply {
            textSize = 13f
            setTextColor(0xCCFFFFFF.toInt())
        }
        heroChip = TextView(this).apply {
            textSize = 12f
            setTextColor(WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = rounded(0x33FFFFFF, 16)
        }
        hero.addView(heroName, matchWrap())
        hero.addView(heroTemp, matchWrap())
        hero.addView(heroLine, matchWrap())
        hero.addView(heroChip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) })
        root.addView(hero, matchWrap().apply { topMargin = dp(16) })
    }

    private fun buildAccountCard(root: LinearLayout) {
        val card = card(root)
        accountChip = cardHeader(card, "Cont MyTESY")

        // Collapsed view once connected.
        accountSummary = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        accountSummaryText = TextView(this).apply {
            textSize = 14f
            setTextColor(INK)
            setLineSpacing(0f, 1.2f)
        }
        accountSummary.addView(accountSummaryText, matchWrap().apply { topMargin = dp(8) })
        button(accountSummary, "Modifică datele contului", BtnStyle.TEXT, top = 4) {
            editingAccount = true
            applyState()
        }
        card.addView(accountSummary, matchWrap())

        // Login form.
        accountForm = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        addText(accountForm, "Folosește emailul și parola din aplicația MyTESY. Datele rămân criptate pe telefon.", 13f, MUTED, top = 6)
        emailInput = field(accountForm, "EMAIL", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        passwordInput = field(accountForm, "PAROLĂ", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, "Arată") { toggle ->
            passwordVisible = !passwordVisible
            passwordInput.transformationMethod = if (passwordVisible) null else PasswordTransformationMethod.getInstance()
            toggle.text = if (passwordVisible) "Ascunde" else "Arată"
            passwordInput.setSelection(passwordInput.text.length)
        }
        // isSingleLine resets the transformation method, so hide the password explicitly.
        passwordInput.transformationMethod = PasswordTransformationMethod.getInstance()
        userIdInput = field(accountForm, "USER ID MYTESY", InputType.TYPE_CLASS_NUMBER)
        addText(accountForm, "Număr din contul tău. Îl găsești în site-ul v4.mytesy.com, în cererea get-my-devices (Network), parametrul userID.", 12f, MUTED, top = 6)
        connectButton = button(accountForm, "Conectează", BtnStyle.PRIMARY, top = 16) { testConnection() }
        card.addView(accountForm, matchWrap())

        deviceSpinner = Spinner(this).apply { visibility = View.GONE }
        deviceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val chosen = discovered.getOrNull(position) ?: return
                settings.selectedMac = chosen.mac
                settings.deviceName = chosen.name
                chosen.currentTempC?.let {
                    settings.lastTempC = it
                    settings.lastTempAt = System.currentTimeMillis()
                }
                updateHero()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        card.addView(deviceSpinner, matchWrap().apply { topMargin = dp(8) })
    }

    private fun buildAutomationCard(root: LinearLayout) {
        automationCard = card(root)
        automationChip = cardHeader(automationCard, "Automatizare")
        addText(automationCard, "Ca la un termostat: pornește convectorul când temperatura scade sub prag și îl oprește când ajunge la prag + histerezis (verificat din 5 în 5 minute), cel târziu după durata maximă. Cu „Setează ținta pe aparat”, aplicația pune singură pe convector temperatura de oprire la fiecare pornire.", 13f, MUTED, top = 6)
        thresholdInput = settingRow(automationCard, "Prag temperatură", "Pornește sub această valoare", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, "°C")
        hysteresisInput = settingRow(automationCard, "Histerezis", "Oprește la prag + această valoare", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, "°C")
        val targetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val targetTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        targetTexts.addView(TextView(this).apply {
            text = "Setează ținta pe aparat"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(INK)
        })
        targetTexts.addView(TextView(this).apply {
            text = "La pornire pune pe convector temperatura de oprire (rotunjită în sus)"
            textSize = 12f
            setTextColor(MUTED)
        })
        targetRow.addView(targetTexts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        targetSwitch = android.widget.Switch(this).apply {
            isChecked = settings.setDeviceTarget
            setOnCheckedChangeListener { _, checked -> settings.setDeviceTarget = checked }
        }
        targetRow.addView(targetSwitch, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        automationCard.addView(targetRow, matchWrap().apply { topMargin = dp(16) })
        durationInput = settingRow(automationCard, "Durată maximă pornire", "Între 5 și 60 de minute", InputType.TYPE_CLASS_NUMBER, "min")
        intervalInput = settingRow(automationCard, "Verificare la fiecare", "Între 1 și 12 ore", InputType.TYPE_CLASS_NUMBER, "ore")
        automationSummary = TextView(this).apply {
            textSize = 13f
            setTextColor(BRAND)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFFF7F0F9.toInt(), 12)
            setLineSpacing(0f, 1.15f)
        }
        automationCard.addView(automationSummary, matchWrap().apply { topMargin = dp(18) })
        toggleButton = button(automationCard, "Activează automatizarea", BtnStyle.PRIMARY, top = 14) { toggleAutomation() }
        button(automationCard, "Verifică acum", BtnStyle.SECONDARY, top = 8) {
            if (saveAll(showMessage = false)) {
                AutomationScheduler.checkNow(this)
                showStatus("Verificare solicitată. Rezultatul apare în Istoric, mai jos. Android o poate amâna dacă telefonul e în economisire agresivă a bateriei.", TONE_INFO)
                scheduleLogRefresh()
            }
        }
        button(automationCard, "Salvează setările", BtnStyle.TEXT, top = 2) { saveAll(showMessage = true) }
        addText(automationCard, "Aplicația doar pornește și oprește convectorul. Încălzește până la temperatura setată pe aparat (Comfort/Eco), deci setează-o înainte la valoarea dorită.", 12f, MUTED, top = 6)
    }

    private fun buildPermissionsCard(root: LinearLayout) {
        permissionsCard = card(root)
        permissionsChip = cardHeader(permissionsCard, "Permisiuni")
        addText(permissionsCard, "Ca oprirea să se facă la timp, chiar cu ecranul stins.", 13f, MUTED, top = 6)
        rowExact = permissionRow(permissionsCard, "Oprire la minut exact", "Fără ea, oprirea poate întârzia câteva minute.", "Permite") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                openSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            } else {
                showStatus("Pe această versiune de Android oprirea la minut exact e permisă implicit.", TONE_INFO)
            }
        }
        rowBattery = permissionRow(permissionsCard, "Fără economisire baterie", "Fără ea, verificările pot întârzia ore.", "Permite") {
            openSettings(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        }
        rowNotif = permissionRow(permissionsCard, "Notificări", "Te anunță când pornește și se oprește convectorul.", "Permite") {
            try {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            } catch (ignored: ActivityNotFoundException) {
                openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            }
        }
        addText(permissionsCard, "Pe OnePlus verifică și: Setări → Aplicații → TESY Balcony Controller → Utilizare baterie → Nerestricționat.", 12f, MUTED, top = 10)
    }

    private fun buildLogCard(root: LinearLayout) {
        val card = card(root)
        val refresh = cardHeader(card, "Istoric")
        setChip(refresh, "Reîmprospătează", BRAND, NEUTRAL_BG)
        refresh.setOnClickListener { refreshLog() }
        logView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(INK)
            setLineSpacing(0f, 1.15f)
        }
        card.addView(logView, matchWrap().apply { topMargin = dp(10) })
    }

    // ---------- UI helpers ----------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun rounded(fill: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun card(parent: LinearLayout): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(WHITE, 18, 0xFFE9E3ED.toInt())
            setPadding(dp(16), dp(14), dp(16), dp(16))
            elevation = dp(1).toFloat()
        }
        parent.addView(c, matchWrap().apply { topMargin = dp(14) })
        return c
    }

    /** Adds a title row to a card and returns the chip on its right. */
    private fun cardHeader(card: LinearLayout, title: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(INK)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val chip = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(5), dp(10), dp(5))
            visibility = View.GONE
        }
        row.addView(chip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        card.addView(row, matchWrap())
        return chip
    }

    private fun setChip(chip: TextView, text: String?, fg: Int, bg: Int) {
        if (text == null) {
            chip.visibility = View.GONE
        } else {
            chip.text = text
            chip.setTextColor(fg)
            chip.background = rounded(bg, 12)
            chip.visibility = View.VISIBLE
        }
    }

    private fun addText(parent: LinearLayout, value: String, size: Float, color: Int, top: Int = 0): TextView {
        val view = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            setLineSpacing(0f, 1.15f)
        }
        parent.addView(view, matchWrap().apply { topMargin = dp(top) })
        return view
    }

    private fun field(
        parent: LinearLayout,
        label: String,
        type: Int,
        actionLabel: String? = null,
        onAction: ((TextView) -> Unit)? = null
    ): EditText {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(TextView(this).apply {
            text = label
            textSize = 11f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(MUTED)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (actionLabel != null && onAction != null) {
            row.addView(TextView(this).apply {
                text = actionLabel
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(BRAND)
                setPadding(dp(8), 0, 0, 0)
                setOnClickListener { onAction(this) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        parent.addView(row, matchWrap().apply { topMargin = dp(14) })
        val input = EditText(this).apply {
            inputType = type
            textSize = 16f
            setTextColor(INK)
            isSingleLine = true
            background = rounded(0xFFF7F4F9.toInt(), 12, 0xFFDCD3E0.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        parent.addView(input, matchWrap().apply { topMargin = dp(6) })
        return input
    }

    private fun settingRow(parent: LinearLayout, label: String, hint: String, type: Int, suffix: String): EditText {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = label
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(INK)
        })
        texts.addView(TextView(this).apply {
            text = hint
            textSize = 12f
            setTextColor(MUTED)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val input = EditText(this).apply {
            inputType = type
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isSingleLine = true
            setTextColor(BRAND)
            background = rounded(0xFFF7F4F9.toInt(), 12, 0xFFDCD3E0.toInt())
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }
        row.addView(input, LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(TextView(this).apply {
            text = suffix
            textSize = 13f
            setTextColor(MUTED)
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.addView(row, matchWrap().apply { topMargin = dp(16) })
        return input
    }

    private fun styleButton(b: Button, style: BtnStyle) {
        val fill: Int
        val fg: Int
        when (style) {
            BtnStyle.PRIMARY -> { fill = BRAND; fg = WHITE }
            BtnStyle.SECONDARY -> { fill = 0xFFF0E8F3.toInt(); fg = BRAND }
            BtnStyle.TEXT -> { fill = 0x00000000; fg = BRAND }
        }
        b.setTextColor(fg)
        b.background = RippleDrawable(ColorStateList.valueOf(0x22000000), rounded(fill, 14), null)
    }

    private fun button(parent: LinearLayout, label: String, style: BtnStyle, top: Int = 12, action: () -> Unit): Button {
        val b = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            stateListAnimator = null
            minHeight = dp(48)
            minimumHeight = dp(48)
            setOnClickListener { action() }
        }
        styleButton(b, style)
        parent.addView(b, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
        ).apply { topMargin = dp(top) })
        return b
    }

    private fun permissionRow(parent: LinearLayout, title: String, subtitle: String, actionLabel: String, onAction: () -> Unit): PermRow {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val badge = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        row.addView(badge, LinearLayout.LayoutParams(dp(28), dp(28)))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(INK)
        })
        texts.addView(TextView(this).apply {
            text = subtitle
            textSize = 12f
            setTextColor(MUTED)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val action = TextView(this).apply {
            text = actionLabel
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(BRAND)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(NEUTRAL_BG, 12)
            setOnClickListener { onAction() }
        }
        row.addView(action, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        parent.addView(row, matchWrap().apply { topMargin = dp(14) })
        return PermRow(badge, action)
    }

    private fun bindPermission(row: PermRow, granted: Boolean) {
        row.badge.text = if (granted) "✓" else "!"
        row.badge.setTextColor(if (granted) OK_FG else WARN_FG)
        row.badge.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (granted) OK_BG else WARN_BG)
        }
        row.action.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    } catch (ignored: Exception) { "" }

    // ---------- State ----------

    private fun isConfigured(): Boolean = secure.load() != null && !settings.selectedMac.isNullOrBlank()

    /** Apps updated from an older build have no flag yet: derive it from the queued periodic work. */
    private fun syncAutomationFlag() {
        if (settings.hasAutomationFlag) return
        lifecycleScope.launch {
            val scheduled = withContext(Dispatchers.IO) { AutomationScheduler.isPeriodicScheduled(this@MainActivity) }
            settings.automationEnabled = scheduled
            applyState()
        }
    }

    private fun applyState() {
        val configured = isConfigured()
        val collapsed = configured && !editingAccount
        accountSummary.visibility = if (collapsed) View.VISIBLE else View.GONE
        accountForm.visibility = if (collapsed) View.GONE else View.VISIBLE
        automationCard.visibility = if (configured) View.VISIBLE else View.GONE
        permissionsCard.visibility = if (configured) View.VISIBLE else View.GONE

        when {
            !configured -> setChip(accountChip, null, 0, 0)
            connectionOk == true -> setChip(accountChip, "✓ Conectat", OK_FG, OK_BG)
            connectionOk == false -> setChip(accountChip, "Neverificat", WARN_FG, WARN_BG)
            else -> setChip(accountChip, "Se verifică…", MUTED, NEUTRAL_BG)
        }
        accountSummaryText.text = buildString {
            append(emailInput.text.toString().trim())
            settings.deviceName?.let { append("\nConvector: ").append(it) }
        }

        val enabled = settings.automationEnabled
        toggleButton.text = if (enabled) "Oprește automatizarea" else "Activează automatizarea"
        styleButton(toggleButton, if (enabled) BtnStyle.SECONDARY else BtnStyle.PRIMARY)
        setChip(automationChip, if (enabled) "● Activă" else "○ Oprită", if (enabled) OK_FG else MUTED, if (enabled) OK_BG else NEUTRAL_BG)
        updateHero()
        updateAutomationSummary()
    }

    private fun updateAutomationSummary() {
        if (!::automationSummary.isInitialized) return
        automationSummary.text = "Pornește sub ${Fmt.temp(settings.thresholdC)} · oprește la ${Fmt.temp(settings.stopTargetC)} (cel mult ${settings.durationMinutes} min)"
    }

    private fun updateHero() {
        val configured = isConfigured()
        val temp = settings.lastTempC
        heroName.text = if (configured) (settings.deviceName ?: "Convector TESY") else "Convector TESY"
        heroTemp.text = temp?.let { Fmt.temp(it) } ?: "–"
        heroLine.text = when {
            !configured -> "Conectează contul MyTESY ca să începi."
            settings.lastTempAt > 0L -> "Temperatura citită la ${Fmt.clock(settings.lastTempAt)}"
            else -> "Se citește temperatura…"
        }
        heroChip.text = when {
            !configured -> "Neconfigurat"
            settings.automationEnabled -> "● Automatizare activă · sub ${Fmt.temp(settings.thresholdC)} · la ${settings.intervalHours} h"
            else -> "○ Automatizare oprită"
        }
    }

    private fun showStatus(value: String, tone: Int = TONE_INFO, scroll: Boolean = true) {
        if (!::banner.isInitialized) return
        val (fg, bg) = when (tone) {
            TONE_OK -> OK_FG to OK_BG
            TONE_WARN -> WARN_FG to WARN_BG
            TONE_ERROR -> ERR_FG to ERR_BG
            else -> INFO_FG to INFO_BG
        }
        banner.text = value
        banner.setTextColor(fg)
        banner.background = rounded(bg, 14)
        banner.visibility = View.VISIBLE
        if (scroll) scrollView?.post { scrollView?.smoothScrollTo(0, 0) }
    }

    private fun scheduleLogRefresh() {
        for (delay in longArrayOf(4_000L, 15_000L, 40_000L)) {
            window.decorView.postDelayed({ refreshLog() }, delay)
        }
    }

    private fun refreshPermissions() {
        if (!::permissionsChip.isInitialized) return
        val exact = OffAlarm.canScheduleExact(this)
        val battery = getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true
        val notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        bindPermission(rowExact, exact)
        bindPermission(rowBattery, battery)
        bindPermission(rowNotif, notifications)
        val missing = listOf(exact, battery, notifications).count { !it }
        if (missing == 0) setChip(permissionsChip, "✓ Totul în regulă", OK_FG, OK_BG)
        else setChip(permissionsChip, "$missing de rezolvat", WARN_FG, WARN_BG)
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

    // ---------- Actions ----------

    private fun loadSavedValues() {
        secure.load()?.let {
            emailInput.setText(it.email)
            passwordInput.setText(it.password)
            userIdInput.setText(it.userId)
        }
        thresholdInput.setText(settings.thresholdC.toString())
        hysteresisInput.setText(settings.hysteresisC.toString())
        durationInput.setText(settings.durationMinutes.toString())
        intervalInput.setText(settings.intervalHours.toString())
    }

    private fun testConnection(silent: Boolean = false) {
        if (!saveAll(showMessage = false)) return
        val credentials = secure.load() ?: return
        connectionOk = null
        connectButton.isEnabled = false
        connectButton.text = "Se conectează…"
        applyState()
        if (!silent) showStatus("Conectare la MyTESY și căutare convector…", TONE_INFO)
        lifecycleScope.launch {
            try {
                val client = TesyCloudClient(credentials)
                val devices = client.getDevices()
                discovered = devices
                if (devices.isEmpty()) {
                    connectionOk = false
                    if (!silent) editingAccount = true
                    showStatus(
                        "Contul a răspuns, dar nu am găsit un convector compatibil. Nu trimitem comenzi. Detalii tehnice (doar nume de câmpuri, fără date personale):\n${client.lastDiagnostics}",
                        TONE_WARN, scroll = !silent
                    )
                    return@launch
                }
                val labels = devices.map {
                    "${it.name} — ${it.modelType.ifBlank { it.model }} — ${it.currentTempC?.let { t -> Fmt.temp(t) } ?: "temperatură indisponibilă"}"
                }
                deviceSpinner.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, labels)
                val wanted = settings.selectedMac?.let { TesyCloudClient.normaliseMac(it) }
                val index = devices.indexOfFirst { TesyCloudClient.normaliseMac(it.mac) == wanted }.let { if (it >= 0) it else 0 }
                deviceSpinner.setSelection(index)
                deviceSpinner.visibility = if (devices.size > 1) View.VISIBLE else View.GONE
                val chosen = devices[index]
                settings.selectedMac = chosen.mac
                settings.deviceName = chosen.name
                chosen.currentTempC?.let {
                    settings.lastTempC = it
                    settings.lastTempAt = System.currentTimeMillis()
                }
                connectionOk = true
                editingAccount = false
                if (!silent) {
                    showStatus(
                        "Conectat la „${chosen.name}” (${chosen.modelType.ifBlank { chosen.model }}). Mai jos poți configura și porni automatizarea.",
                        TONE_OK
                    )
                } else if (banner.visibility == View.VISIBLE) {
                    banner.visibility = View.GONE
                }
            } catch (e: Exception) {
                connectionOk = false
                if (!silent) editingAccount = true
                showStatus(e.message ?: "Nu am putut verifica MyTESY.", TONE_ERROR, scroll = !silent)
            } finally {
                connectButton.isEnabled = true
                connectButton.text = "Conectează"
                applyState()
            }
        }
    }

    private fun toggleAutomation() {
        if (settings.automationEnabled) {
            AutomationScheduler.stop(this)
            settings.automationEnabled = false
            EventLog.add(this, "Automatizare oprită de utilizator.")
            refreshLog()
            showStatus("Verificările viitoare au fost oprite. Un ciclu de încălzire deja început își va executa oprirea de siguranță.", TONE_INFO)
        } else {
            if (!saveAll(showMessage = false)) return
            if (settings.selectedMac.isNullOrBlank()) {
                showStatus("Mai întâi conectează-te și alege convectorul.", TONE_WARN)
                return
            }
            AutomationScheduler.start(this, settings.intervalHours)
            AutomationScheduler.checkNow(this)
            settings.automationEnabled = true
            EventLog.add(this, "Automatizare activată: pornește sub ${settings.thresholdC}°C, oprește la ${settings.stopTargetC}°C, maxim ${settings.durationMinutes} min, la fiecare ${settings.intervalHours} ore.")
            refreshLog()
            scheduleLogRefresh()
            showStatus("Automatizare activă. Prima verificare a fost solicitată acum; următoarele vor fi la fiecare ${settings.intervalHours} ore.", TONE_OK)
        }
        applyState()
    }

    private fun saveAll(showMessage: Boolean): Boolean {
        val email = emailInput.text.toString().trim()
        val password = passwordInput.text.toString()
        val userId = userIdInput.text.toString().trim()
        if (email.isBlank() || password.isBlank()) {
            editingAccount = true
            applyState()
            showStatus("Completează emailul și parola MyTESY.", TONE_WARN)
            return false
        }
        val threshold = thresholdInput.text.toString().replace(',', '.').toDoubleOrNull()
        val hysteresis = hysteresisInput.text.toString().replace(',', '.').toDoubleOrNull()
        val duration = durationInput.text.toString().toIntOrNull()
        val interval = intervalInput.text.toString().toIntOrNull()
        if (threshold == null || !threshold.isFinite() || threshold !in 0.0..30.0 || hysteresis == null || !hysteresis.isFinite() || hysteresis !in 0.0..10.0 || duration == null || duration !in 5..60 || interval == null || interval !in 1..12) {
            showStatus("Setări invalide. Prag: 0–30°C; histerezis: 0–10°C; durată: 5–60 minute; interval: 1–12 ore.", TONE_WARN)
            return false
        }
        val previousInterval = settings.intervalHours
        secure.save(TesyCredentials(email, password, userId))
        settings.thresholdC = threshold
        settings.hysteresisC = hysteresis
        settings.durationMinutes = duration
        settings.intervalHours = interval
        if (discovered.isNotEmpty()) {
            val selected = discovered.getOrNull(deviceSpinner.selectedItemPosition)
            if (selected != null) settings.selectedMac = selected.mac
        }
        // Apply a changed check interval to an already active automation.
        if (settings.automationEnabled && previousInterval != interval) {
            AutomationScheduler.start(this, interval)
        }
        updateHero()
        updateAutomationSummary()
        if (showMessage) showStatus("Setările au fost salvate pe telefon. Datele contului sunt criptate cu Android Keystore.", TONE_OK)
        return true
    }
}
