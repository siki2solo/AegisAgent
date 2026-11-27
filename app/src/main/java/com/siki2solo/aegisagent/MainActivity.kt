package com.siki2solo.aegisagent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_NAME = "aegis_agent_prefs"
        private const val REQ_POST_NOTIFICATIONS = 2041
    }

    private lateinit var store: MacroAndAuditStore
    private val brainClient = HybridBrainClient()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var tabContentContainer: FrameLayout
    private lateinit var contentScroll: ScrollView
    private lateinit var btnToggleOverlay: Button
    private lateinit var headerStatus: TextView

    private lateinit var labelNavHome: TextView
    private lateinit var labelNavBrains: TextView
    private lateinit var labelNavShield: TextView
    private lateinit var labelNavAudit: TextView

    private var activeTabIndex: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = MacroAndAuditStore(applicationContext)
        tabContentContainer = findViewById(R.id.tabContentContainer)
        contentScroll = findViewById(R.id.contentScroll)
        btnToggleOverlay = findViewById(R.id.btnToggleOverlay)
        headerStatus = findViewById(R.id.headerStatus)

        labelNavHome = findViewById(R.id.labelNavHome)
        labelNavBrains = findViewById(R.id.labelNavBrains)
        labelNavShield = findViewById(R.id.labelNavShield)
        labelNavAudit = findViewById(R.id.labelNavAudit)

        applySystemBarInsets()
        wireNavigation()
        renderActiveTab()
        refreshOverlayButton()
    }

    override fun onResume() {
        super.onResume()
        refreshOverlayButton()
        if (activeTabIndex == 0 || activeTabIndex == 3) {
            renderActiveTab()
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Preserve active tab & scroll position across portrait/landscape rotation
        refreshOverlayButton()
    }

    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.rootContainer)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun wireNavigation() {
        findViewById<View>(R.id.navHome).setOnClickListener { selectTab(0) }
        findViewById<View>(R.id.navBrains).setOnClickListener { selectTab(1) }
        findViewById<View>(R.id.navShield).setOnClickListener { selectTab(2) }
        findViewById<View>(R.id.navAudit).setOnClickListener { selectTab(3) }

        btnToggleOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                openOverlayPermissionSettings()
                return@setOnClickListener
            }
            val intent = Intent(this, AegisOverlayService::class.java)
            if (AegisOverlayService.isRunning) {
                stopService(intent)
            } else {
                ContextCompat.startForegroundService(this, intent)
            }
            mainHandler.postDelayed({ refreshOverlayButton() }, 250L)
        }
    }

    private fun selectTab(index: Int) {
        activeTabIndex = index
        contentScroll.scrollTo(0, 0)
        renderActiveTab()
    }

    private fun refreshOverlayButton() {
        val running = AegisOverlayService.isRunning
        btnToggleOverlay.text = getString(if (running) R.string.action_stop_orb else R.string.action_start_orb)
        val tintColor = if (running) Color.parseColor("#EF4444") else Color.parseColor(AegisDefaults.ORB_ACCENT_HEX)
        btnToggleOverlay.setBackgroundColor(tintColor)
        btnToggleOverlay.setTextColor(if (running) Color.WHITE else Color.parseColor("#0B0F19"))
        headerStatus.text = if (running) {
            "Floating Orb Active · Double-press Vol-Down to abort"
        } else {
            getString(R.string.subtitle_obsidian_shield)
        }
    }

    private fun renderActiveTab() {
        val accent = Color.parseColor(AegisDefaults.ORB_ACCENT_HEX)
        val muted = Color.parseColor("#94A3B8")
        labelNavHome.setTextColor(if (activeTabIndex == 0) accent else muted)
        labelNavBrains.setTextColor(if (activeTabIndex == 1) accent else muted)
        labelNavShield.setTextColor(if (activeTabIndex == 2) accent else muted)
        labelNavAudit.setTextColor(if (activeTabIndex == 3) accent else muted)

        tabContentContainer.removeAllViews()
        val contentView = when (activeTabIndex) {
            0 -> buildHomeTab()
            1 -> buildBrainsTab()
            2 -> buildShieldTab()
            else -> buildAuditTab()
        }
        tabContentContainer.addView(contentView)
    }

    private fun buildHomeTab(): View {
        val root = createColumn()
        root.addView(createSectionTitle("System Readiness & Permissions"))

        val overlayOk = Settings.canDrawOverlays(this)
        val accessOk = AegisAccessibilityService.instance != null
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        val notifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        root.addView(
            createActionCard(
                title = "1. Android 14–16 Restricted Settings Unlock",
                subtitle = "Open App Info, tap the 3-dot menu (top-right), and select 'Allow restricted settings' before enabling Accessibility.",
                buttonLabel = "Open App Info",
                accentHex = "#F59E0B"
            ) {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        )

        root.addView(
            createActionCard(
                title = "2. Floating Overlay Permission (${if (overlayOk) "GRANTED" else "REQUIRED"})",
                subtitle = "Allows the Obsidian Shield Orb, Command Pill, and HITL Confirmation Card to hover safely.",
                buttonLabel = if (overlayOk) "Configured" else "Grant Overlay",
                accentHex = if (overlayOk) "#10B981" else "#38BDF8"
            ) { openOverlayPermissionSettings() }
        )

        root.addView(
            createActionCard(
                title = "3. Accessibility Bridge (${if (accessOk) "ACTIVE" else "INACTIVE"})",
                subtitle = "Reads sanitized UI nodes outside blacklisted apps and listens for Volume-Down double-press Kill Switch.",
                buttonLabel = "Accessibility Settings",
                accentHex = if (accessOk) "#10B981" else "#38BDF8"
            ) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        )

        root.addView(
            createActionCard(
                title = "4. Notifications & Battery (${if (notifOk && batteryOk) "READY" else "RECOMMENDED"})",
                subtitle = "Keeps the foreground companion alive and posts persistent safety status notifications.",
                buttonLabel = "Configure Power & Alerts",
                accentHex = "#A78BFA"
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifOk) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        REQ_POST_NOTIFICATIONS
                    )
                } else {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            }
        )

        return root
    }

    private fun buildBrainsTab(): View {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val root = createColumn()
        root.addView(createSectionTitle("Hybrid Brain Configuration"))

        val localUrlInput = createInputField(
            hint = "Local Termux llama-server URL",
            initial = prefs.getString("local_server_url", AegisDefaults.LOCAL_SERVER_URL)
                ?: AegisDefaults.LOCAL_SERVER_URL
        )
        val localModelInput = createInputField(
            hint = "Local GGUF Model ID",
            initial = prefs.getString("local_model_id", AegisDefaults.LOCAL_MODEL_ID)
                ?: AegisDefaults.LOCAL_MODEL_ID
        )
        val geminiModelInput = createInputField(
            hint = "Cloud Gemini Model",
            initial = prefs.getString("gemini_model", AegisDefaults.GEMINI_MODEL)
                ?: AegisDefaults.GEMINI_MODEL
        )
        val geminiKeyInput = createInputField(
            hint = "Gemini API Key (stored locally on device)",
            initial = prefs.getString("gemini_api_key", "") ?: ""
        )
        val thinkingInput = createInputField(
            hint = "Thinking Level (low | medium | high)",
            initial = prefs.getString("thinking_level", AegisDefaults.DEFAULT_THINKING_LEVEL)
                ?: AegisDefaults.DEFAULT_THINKING_LEVEL
        )

        val groundingCheck = CheckBox(this).apply {
            text = "Enable Google Search Grounding (auto-falls back on free tier)"
            setTextColor(Color.parseColor("#E2E8F0"))
            isChecked = prefs.getBoolean("enable_grounding", AegisDefaults.ENABLE_SEARCH_GROUNDING)
        }
        val strictHitlCheck = CheckBox(this).apply {
            text = "Strict HITL Confirmation on External Messaging Apps"
            setTextColor(Color.parseColor("#E2E8F0"))
            isChecked = prefs.getBoolean("strict_hitl_external", AegisDefaults.STRICT_HITL_EXTERNAL)
        }

        val probeOutput = TextView(this).apply {
            text = "Connection diagnostics ready."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, dp(8), 0, dp(8))
        }

        val saveBtn = createStyledButton("Save Brain Config", "#10B981", "#0B0F19") {
            val thinking = thinkingInput.text.toString().trim().lowercase()
                .takeIf { it in setOf("low", "medium", "high") } ?: "medium"
            prefs.edit()
                .putString("local_server_url", localUrlInput.text.toString().trim())
                .putString("local_model_id", localModelInput.text.toString().trim())
                .putString("gemini_model", geminiModelInput.text.toString().trim())
                .putString("gemini_api_key", geminiKeyInput.text.toString().trim())
                .putString("thinking_level", thinking)
                .putBoolean("enable_grounding", groundingCheck.isChecked)
                .putBoolean("strict_hitl_external", strictHitlCheck.isChecked)
                .apply()
            Toast.makeText(this, "Brain configuration saved", Toast.LENGTH_SHORT).show()
        }

        val pingLocalBtn = createStyledButton("Ping Local Termux Server", "#38BDF8", "#0B0F19") {
            probeOutput.text = "Pinging local reflex endpoint..."
            val url = localUrlInput.text.toString().trim()
            executor.execute {
                val (ok, msg) = brainClient.pingLocalServer(url)
                mainHandler.post {
                    probeOutput.setTextColor(Color.parseColor(if (ok) "#10B981" else "#EF4444"))
                    probeOutput.text = msg
                }
            }
        }

        val testCloudBtn = createStyledButton("Test Gemini Cloud Key", "#A78BFA", "#0B0F19") {
            probeOutput.text = "Testing Gemini consultant key..."
            val key = geminiKeyInput.text.toString().trim()
            val model = geminiModelInput.text.toString().trim()
            val level = thinkingInput.text.toString().trim().ifBlank { "medium" }
            executor.execute {
                val (ok, msg) = brainClient.testCloudKey(key, model, level)
                mainHandler.post {
                    probeOutput.setTextColor(Color.parseColor(if (ok) "#10B981" else "#EF4444"))
                    probeOutput.text = msg
                }
            }
        }

        root.addView(localUrlInput)
        root.addView(localModelInput)
        root.addView(geminiModelInput)
        root.addView(geminiKeyInput)
        root.addView(thinkingInput)
        root.addView(groundingCheck)
        root.addView(strictHitlCheck)
        root.addView(saveBtn)
        root.addView(pingLocalBtn)
        root.addView(testCloudBtn)
        root.addView(probeOutput)
        return root
    }

    private fun buildShieldTab(): View {
        val root = createColumn()
        root.addView(createSectionTitle("Blacklist & Deterministic Macros"))

        val customInput = createInputField("Add package to Custom Blacklist (e.g. com.bank.app)", "")
        val addBtn = createStyledButton("Add to Custom Blacklist", "#EF4444", "#FFFFFF") {
            val pkgToAdd = customInput.text.toString().trim()
            if (pkgToAdd.contains(".")) {
                val updated = PolicyGatekeeper.loadCustomBlacklist(applicationContext).toMutableSet()
                updated.add(pkgToAdd)
                PolicyGatekeeper.saveCustomBlacklist(applicationContext, updated)
                Toast.makeText(this, "Added $pkgToAdd to blacklist", Toast.LENGTH_SHORT).show()
                renderActiveTab()
            }
        }
        root.addView(customInput)
        root.addView(addBtn)

        val customList = PolicyGatekeeper.loadCustomBlacklist(applicationContext).sorted()
        root.addView(createSectionSubtitle("Custom Blacklist (${customList.size})"))
        if (customList.isEmpty()) {
            root.addView(createMutedNote("No custom packages configured."))
        } else {
            for (pkgName in customList) {
                root.addView(
                    createActionCard(
                        title = pkgName,
                        subtitle = "User-defined shielded surface",
                        buttonLabel = "Remove",
                        accentHex = "#64748B"
                    ) {
                        val updated = PolicyGatekeeper.loadCustomBlacklist(applicationContext).toMutableSet()
                        updated.remove(pkgName)
                        PolicyGatekeeper.saveCustomBlacklist(applicationContext, updated)
                        renderActiveTab()
                    }
                )
            }
        }

        root.addView(createSectionSubtitle("Immutable System Blacklist (${PolicyGatekeeper.SYSTEM_BLACKLIST.size})"))
        val sysSummary = TextView(this).apply {
            text = PolicyGatekeeper.SYSTEM_BLACKLIST.sorted().joinToString("\n")
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = createCardDrawable("#131A2A")
        }
        root.addView(sysSummary)

        val macros = store.loadMacros()
        root.addView(createSectionSubtitle("Approved Tier 0 Macros (${macros.size})"))
        for (macro in macros) {
            val tv = TextView(this).apply {
                text = "${macro.name}\nPackage: ${macro.targetPackage} · Steps: ${macro.steps.size}"
                setTextColor(Color.parseColor("#E2E8F0"))
                textSize = 12f
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = createCardDrawable("#131A2A")
            }
            root.addView(tv)
        }

        val exportLoraBtn = createStyledButton("Export Approved LoRA JSONL", "#38BDF8", "#0B0F19") {
            val outFile = store.exportApprovedLoraJsonl()
            Toast.makeText(this, "Exported to ${outFile.name}", Toast.LENGTH_LONG).show()
        }
        root.addView(exportLoraBtn)

        return root
    }

    private fun buildAuditTab(): View {
        val root = createColumn()
        root.addView(createSectionTitle("Append-Only Audit Log (audit_log.jsonl)"))

        val refreshBtn = createStyledButton("Refresh Audit Trace", "#38BDF8", "#0B0F19") {
            renderActiveTab()
        }
        root.addView(refreshBtn)

        val records = store.readRecentAudit(60)
        if (records.isEmpty()) {
            root.addView(createMutedNote("No guardrail events logged yet. Launch the Orb to start recording."))
        } else {
            for (rec in records) {
                val card = TextView(this).apply {
                    text = "[${rec.outcome}] ${rec.actionType} · ${rec.brainTier}\n" +
                        "Pkg: ${rec.packageName} · ${rec.timestampIso}\n" +
                        rec.details
                    setTextColor(Color.parseColor("#E2E8F0"))
                    textSize = 12f
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = createCardDrawable("#131A2A")
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.bottomMargin = dp(8)
                    layoutParams = lp
                }
                root.addView(card)
            }
        }
        return root
    }

    private fun openOverlayPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun createColumn(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun createSectionTitle(label: String): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 17f
            setPadding(0, 0, 0, dp(12))
        }
    }

    private fun createSectionSubtitle(label: String): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 14f
            setPadding(0, dp(14), 0, dp(6))
        }
    }

    private fun createMutedNote(label: String): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 13f
            setPadding(0, dp(6), 0, dp(10))
        }
    }

    private fun createInputField(hint: String, initial: String): EditText {
        return EditText(this).apply {
            this.hint = hint
            setText(initial)
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 13f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = createCardDrawable("#131A2A")
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(10)
            layoutParams = lp
        }
    }

    private fun createStyledButton(
        label: String,
        bgHex: String,
        textHex: String,
        onClick: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setBackgroundColor(Color.parseColor(bgHex))
            setTextColor(Color.parseColor(textHex))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(10)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun createActionCard(
        title: String,
        subtitle: String,
        buttonLabel: String,
        accentHex: String,
        onClick: () -> Unit
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = createCardDrawable("#131A2A")
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(10)
            layoutParams = lp
        }

        val titleTv = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 14f
        }
        val subTv = TextView(this).apply {
            text = subtitle
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, dp(4), 0, dp(10))
        }
        val btn = Button(this).apply {
            text = buttonLabel
            isAllCaps = false
            setBackgroundColor(Color.parseColor(accentHex))
            setTextColor(Color.parseColor("#0B0F19"))
            setOnClickListener { onClick() }
        }

        card.addView(titleTv)
        card.addView(subTv)
        card.addView(btn)
        return card
    }

    private fun createCardDrawable(bgHex: String): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(bgHex))
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), Color.parseColor("#24314A"))
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
