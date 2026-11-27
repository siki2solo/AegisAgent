package com.siki2solo.aegisagent

import android.Manifest
import android.app.DownloadManager
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
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
    private var termuxFallbackExpanded: Boolean = false
    private val activeDownloads = mutableMapOf<String, Long>()
    private var engineProbeStatus: String = "Select or download a .litertlm or .gguf model to run fully offline."

    private val modelPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        engineProbeStatus = "Importing selected model file..."
        renderActiveTab()
        executor.execute {
            val (_, msg) = ModelManager.importDocumentUri(applicationContext, uri)
            mainHandler.post {
                engineProbeStatus = msg
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                renderActiveTab()
            }
        }
    }

    private val downloadPoller = object : Runnable {
        override fun run() {
            if (activeDownloads.isEmpty()) return
            var anyActive = false
            val completedIds = mutableListOf<String>()
            for ((modelId, downloadId) in activeDownloads) {
                val info = ModelManager.queryDownloadStatus(applicationContext, downloadId)
                if (info.status == DownloadManager.STATUS_RUNNING || info.status == DownloadManager.STATUS_PENDING) {
                    anyActive = true
                } else {
                    completedIds.add(modelId)
                }
            }
            for (doneId in completedIds) {
                activeDownloads.remove(doneId)
            }
            if (activeTabIndex == 1) {
                renderActiveTab()
            }
            if (anyActive) {
                mainHandler.postDelayed(this, 1500L)
            }
        }
    }

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
        mainHandler.removeCallbacks(downloadPoller)
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
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
            mainHandler.postDelayed({
                refreshOverlayButton()
                if (activeTabIndex == 0) renderActiveTab()
            }, 250L)
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
            "Orb Active · ${ModelManager.activeStatusSummary()}"
        } else {
            "Dual-Engine Standalone · LiteRT-LM + llama.cpp GGUF"
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
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val root = createColumn()

        val overlayOk = Settings.canDrawOverlays(this)
        val accessOk = AegisAccessibilityService.instance != null
        val brainReady = ModelManager.isAnyLocalEngineLoaded() ||
            !prefs.getString("gemini_api_key", "").isNullOrBlank()
        val orbRunning = AegisOverlayService.isRunning
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        val notifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        // 4-Item Quick Setup Checklist Card at top of Home tab
        val doneCount = listOf(overlayOk, accessOk, brainReady, orbRunning).count { it }
        val checklistCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = createCardDrawable("#131A2A")
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(14)
            layoutParams = lp
        }
        checklistCard.addView(
            TextView(this).apply {
                text = "Quick Setup Checklist ($doneCount/4 Complete)"
                setTextColor(Color.parseColor("#F8FAFC"))
                textSize = 15f
                setPadding(0, 0, 0, dp(8))
            }
        )
        checklistCard.addView(createChecklistRow("1. Overlay Permission", overlayOk))
        checklistCard.addView(createChecklistRow("2. Restricted Settings + Accessibility Bridge", accessOk))
        checklistCard.addView(createChecklistRow("3. Local Engine Loaded OR Cloud Key Saved", brainReady))
        checklistCard.addView(createChecklistRow("4. Companion Orb Running", orbRunning))
        root.addView(checklistCard)

        root.addView(createSectionTitle("Device Permissions & Onboarding"))

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
                subtitle = "Allows the Obsidian Shield Orb, Command Pill Live Response Box, and HITL Confirmation Card to hover safely.",
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
                subtitle = "Keeps the foreground companion alive during offline model inference.",
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

        root.addView(createSectionTitle("Unified Dual-Engine Model Manager"))

        // Active Engine Status & Controls Card
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = createCardDrawable("#131A2A")
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(12)
            layoutParams = lp
        }
        statusCard.addView(
            TextView(this).apply {
                text = "Active Engine: ${ModelManager.activeStatusSummary()}"
                setTextColor(Color.parseColor(if (ModelManager.isAnyLocalEngineLoaded()) "#38BDF8" else "#94A3B8"))
                textSize = 13f
            }
        )
        statusCard.addView(
            TextView(this).apply {
                text = engineProbeStatus
                setTextColor(Color.parseColor("#CBD5E1"))
                textSize = 12f
                setPadding(0, dp(6), 0, dp(10))
            }
        )

        val gpuAutoEnabled = ModelManager.isGpuAutoEnabled(applicationContext)
        val backendToggleBtn = createStyledButton(
            label = if (gpuAutoEnabled) "LiteRT Backend: GPU Auto (CPU fallback)" else "LiteRT Backend: CPU Only",
            bgHex = "#1B2438",
            textHex = "#38BDF8"
        ) {
            ModelManager.setGpuAutoEnabled(applicationContext, !gpuAutoEnabled)
            renderActiveTab()
        }
        statusCard.addView(backendToggleBtn)

        val testLocalBtn = createStyledButton("Test Active Local Engine", "#10B981", "#0B0F19") {
            engineProbeStatus = "Running live prompt against active embedded engine..."
            renderActiveTab()
            executor.execute {
                val (_, msg) = ModelManager.testActiveEngine()
                mainHandler.post {
                    engineProbeStatus = msg
                    refreshOverlayButton()
                    renderActiveTab()
                }
            }
        }
        statusCard.addView(testLocalBtn)

        if (ModelManager.isAnyLocalEngineLoaded()) {
            val unloadBtn = createStyledButton("Unload Active Model (Free RAM)", "#EF4444", "#FFFFFF") {
                executor.execute {
                    ModelManager.unloadAll()
                    mainHandler.post {
                        engineProbeStatus = "Native model memory released."
                        refreshOverlayButton()
                        renderActiveTab()
                    }
                }
            }
            statusCard.addView(unloadBtn)
        }

        val importBtn = createStyledButton("Import Local .litertlm or .gguf File", "#38BDF8", "#0B0F19") {
            modelPickerLauncher.launch(arrayOf("*/*"))
        }
        statusCard.addView(importBtn)
        root.addView(statusCard)

        // Installed Local Models Section (Auto-detect by extension)
        val installedFiles = ModelManager.listLocalModelFiles(applicationContext)
        root.addView(createSectionSubtitle("Installed On-Device Models (${installedFiles.size})"))
        if (installedFiles.isEmpty()) {
            root.addView(createMutedNote("No model files in ExternalFilesDir/models yet. Import a file or download one below."))
        } else {
            for (modelFile in installedFiles) {
                val kind = ModelManager.detectEngineKind(modelFile.name)
                val engineTag = if (kind == LocalEngineKind.LITERT_LM) "LiteRT-LM" else "llama.cpp GGUF + Embeddings"
                val isCurrent = ModelManager.activeModelFileName == modelFile.name && ModelManager.isAnyLocalEngineLoaded()
                root.addView(
                    createActionCard(
                        title = modelFile.name,
                        subtitle = "$engineTag · ${ModelManager.formatSizeMb(modelFile.length())}${if (isCurrent) " · LOADED" else ""}",
                        buttonLabel = if (isCurrent) "Reload Model" else "Load Model",
                        accentHex = if (isCurrent) "#10B981" else "#38BDF8"
                    ) {
                        loadLocalModelAsync(modelFile)
                    }
                )
            }
        }

        // 4 Curated One-Tap Download Cards
        root.addView(createSectionSubtitle("Curated Offline Models (One-Tap DownloadManager)"))
        for (spec in ModelManager.CURATED_MODELS) {
            val localFile = File(ModelManager.modelsDir(applicationContext), spec.fileName)
            val activeDlId = activeDownloads[spec.id]
            val dlStatusText = if (activeDlId != null) {
                val info = ModelManager.queryDownloadStatus(applicationContext, activeDlId)
                "Downloading: ${info.percent}% (${"%.0f".format(info.downloadedMb)} MB)"
            } else if (localFile.exists() && localFile.length() > 1024L * 1024L) {
                "Downloaded (${ModelManager.formatSizeMb(localFile.length())})"
            } else {
                "Ready to download to app storage"
            }

            val fullSubtitle = buildString {
                append(spec.badge)
                append("\nStatus: ")
                append(dlStatusText)
                if (spec.note.isNotBlank()) {
                    append("\n\nNote: ")
                    append(spec.note)
                }
            }

            root.addView(
                createActionCard(
                    title = "${spec.displayName} (${spec.fileName})",
                    subtitle = fullSubtitle,
                    buttonLabel = if (localFile.exists() && activeDlId == null) "Load Downloaded Model" else "Download Model",
                    accentHex = if (spec.engineKind == LocalEngineKind.LLAMA_GGUF) "#F59E0B" else "#38BDF8"
                ) {
                    if (localFile.exists() && activeDlId == null) {
                        loadLocalModelAsync(localFile)
                    } else {
                        val dlId = ModelManager.enqueueCuratedDownload(applicationContext, spec)
                        activeDownloads[spec.id] = dlId
                        Toast.makeText(this, "Started DownloadManager for ${spec.fileName}", Toast.LENGTH_SHORT).show()
                        mainHandler.removeCallbacks(downloadPoller)
                        mainHandler.postDelayed(downloadPoller, 1200L)
                        renderActiveTab()
                    }
                }
            )
        }

        // Cloud Consultant Fallback (gemini-4-hydrogen)
        root.addView(createSectionSubtitle("Tier 2 Cloud Consultant Fallback (Gemini)"))

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

        // Collapsed Optional Custom Local Server URL (Termux Fallback)
        val toggleTermuxBtn = createStyledButton(
            label = if (termuxFallbackExpanded) "Hide Optional Termux Server URL Fallback" else "Show Optional Custom Local Server URL (Termux Fallback)",
            bgHex = "#1B2438",
            textHex = "#94A3B8"
        ) {
            termuxFallbackExpanded = !termuxFallbackExpanded
            renderActiveTab()
        }

        val enableTermuxCheck = CheckBox(this).apply {
            text = "Use Custom Termux llama-server Fallback when no embedded model is loaded"
            setTextColor(Color.parseColor("#CBD5E1"))
            isChecked = prefs.getBoolean("enable_termux_fallback", AegisDefaults.ENABLE_TERMUX_FALLBACK)
        }
        val localUrlInput = createInputField(
            hint = "Custom Local Server URL (Termux b11960+)",
            initial = prefs.getString("local_server_url", AegisDefaults.LOCAL_SERVER_URL)
                ?: AegisDefaults.LOCAL_SERVER_URL
        )
        val localModelInput = createInputField(
            hint = "Termux Server Model ID",
            initial = prefs.getString("local_model_id", AegisDefaults.LOCAL_MODEL_ID)
                ?: AegisDefaults.LOCAL_MODEL_ID
        )

        val cloudProbeOutput = TextView(this).apply {
            text = "Cloud & fallback diagnostics ready."
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, dp(6), 0, dp(8))
        }

        val saveBtn = createStyledButton("Save Cloud & Fallback Config", "#10B981", "#0B0F19") {
            val thinking = thinkingInput.text.toString().trim().lowercase()
                .takeIf { it in setOf("low", "medium", "high") } ?: "medium"
            prefs.edit()
                .putString("gemini_model", geminiModelInput.text.toString().trim())
                .putString("gemini_api_key", geminiKeyInput.text.toString().trim())
                .putString("thinking_level", thinking)
                .putBoolean("enable_grounding", groundingCheck.isChecked)
                .putBoolean("strict_hitl_external", strictHitlCheck.isChecked)
                .putBoolean("enable_termux_fallback", enableTermuxCheck.isChecked)
                .putString("local_server_url", localUrlInput.text.toString().trim())
                .putString("local_model_id", localModelInput.text.toString().trim())
                .apply()
            Toast.makeText(this, "Brain settings saved", Toast.LENGTH_SHORT).show()
        }

        val testCloudBtn = createStyledButton("Test Gemini Cloud Key", "#A78BFA", "#0B0F19") {
            cloudProbeOutput.text = "Testing Gemini consultant key..."
            val key = geminiKeyInput.text.toString().trim()
            val model = geminiModelInput.text.toString().trim()
            val level = thinkingInput.text.toString().trim().ifBlank { "medium" }
            executor.execute {
                val (ok, msg) = brainClient.testCloudKey(key, model, level)
                mainHandler.post {
                    cloudProbeOutput.setTextColor(Color.parseColor(if (ok) "#10B981" else "#EF4444"))
                    cloudProbeOutput.text = msg
                }
            }
        }

        root.addView(geminiModelInput)
        root.addView(geminiKeyInput)
        root.addView(thinkingInput)
        root.addView(groundingCheck)
        root.addView(strictHitlCheck)
        root.addView(toggleTermuxBtn)
        if (termuxFallbackExpanded) {
            root.addView(enableTermuxCheck)
            root.addView(localUrlInput)
            root.addView(localModelInput)
            root.addView(
                createStyledButton("Ping Custom Termux Server", "#38BDF8", "#0B0F19") {
                    cloudProbeOutput.text = "Pinging custom Termux server..."
                    val url = localUrlInput.text.toString().trim()
                    executor.execute {
                        val (ok, msg) = brainClient.pingLocalServer(url)
                        mainHandler.post {
                            cloudProbeOutput.setTextColor(Color.parseColor(if (ok) "#10B981" else "#EF4444"))
                            cloudProbeOutput.text = msg
                        }
                    }
                }
            )
        }
        root.addView(saveBtn)
        root.addView(testCloudBtn)
        root.addView(cloudProbeOutput)
        return root
    }

    private fun loadLocalModelAsync(file: File) {
        engineProbeStatus = "Loading ${file.name} (auto-releasing any prior engine)..."
        renderActiveTab()
        executor.execute {
            val (_, msg) = ModelManager.loadModelFile(applicationContext, file)
            mainHandler.post {
                engineProbeStatus = msg
                refreshOverlayButton()
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                renderActiveTab()
            }
        }
    }

    private fun buildShieldTab(): View {
        val root = createColumn()
        root.addView(createSectionTitle("Blacklist & Toggleable Tier 0 Macros"))

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
        root.addView(createSectionSubtitle("Starter & Approved Macros (${macros.size})"))
        for (macro in macros) {
            val toggle = CheckBox(this).apply {
                text = "${macro.name} (${macro.targetPackage})${if (macro.isStarter) " · Starter" else ""}"
                setTextColor(Color.parseColor("#E2E8F0"))
                isChecked = macro.enabled
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnCheckedChangeListener { _, isChecked ->
                    store.setMacroEnabled(macro.id, isChecked)
                }
            }
            root.addView(toggle)
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

    private fun createChecklistRow(label: String, done: Boolean): TextView {
        return TextView(this).apply {
            text = "${if (done) "[DONE]" else "[PENDING]"} $label"
            setTextColor(Color.parseColor(if (done) "#10B981" else "#F59E0B"))
            textSize = 12f
            setPadding(0, dp(3), 0, dp(3))
        }
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
