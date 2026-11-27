package com.siki2solo.aegisagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import kotlin.math.abs

class AegisOverlayService : Service() {

    companion object {
        private const val CHANNEL_ID = "aegis_overlay_channel"
        private const val NOTIFICATION_ID = 4108
        private const val PREFS_NAME = "aegis_agent_prefs"

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private lateinit var windowManager: WindowManager
    private lateinit var store: MacroAndAuditStore
    private val brainClient = HybridBrainClient()
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var orbView: LinearLayout? = null
    private var orbParams: WindowManager.LayoutParams? = null
    private var commandPillView: LinearLayout? = null
    private var hitlCardView: LinearLayout? = null

    private var statusTextView: TextView? = null
    private var goalInputView: EditText? = null
    private var modeToggleBtn: Button? = null

    private var useCloudMode: Boolean = false
    private var currentVisualState: OrbVisualState = OrbVisualState.IDLE
    private var speechRecognizer: SpeechRecognizer? = null
    private var pendingHitlAction: ProposedAction? = null

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AegisAccessibilityService.ACTION_SHIELD_STATE -> {
                    val shielded = intent.getBooleanExtra(AegisAccessibilityService.EXTRA_SHIELDED, false)
                    if (shielded) {
                        dismissHitlCard()
                        collapseCommandPill()
                        setOrbVisualState(OrbVisualState.SHIELDED)
                        updateStatusLabel("SHIELDED · Protected app detected")
                    } else if (currentVisualState == OrbVisualState.SHIELDED) {
                        setOrbVisualState(OrbVisualState.IDLE)
                        updateStatusLabel("Ready · Local Reflex standby")
                    }
                }
                AegisAccessibilityService.ACTION_KILL_SWITCH -> {
                    dismissHitlCard()
                    setOrbVisualState(OrbVisualState.KILLED)
                    updateStatusLabel("KILL SWITCH TRIGGERED · All runs aborted")
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        store = MacroAndAuditStore(applicationContext)

        startForegroundSafely()
        registerInternalReceivers()
        attachFloatingOrb()
    }

    override fun onDestroy() {
        isRunning = false
        try {
            unregisterReceiver(stateReceiver)
        } catch (_: Exception) {
        }
        speechRecognizer?.destroy()
        removeViewSafe(orbView)
        removeViewSafe(commandPillView)
        removeViewSafe(hitlCardView)
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        orbParams?.let { params ->
            clampOrbToSafeZone(params)
            orbView?.let { windowManager.updateViewLayout(it, params) }
        }
    }

    private fun startForegroundSafely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AegisAgent Floating Companion",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val openIntent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Floating Obsidian Shield orb active · Double-press Vol-Down to abort")
            .setSmallIcon(R.drawable.ic_aegis_shield)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun registerInternalReceivers() {
        val filter = IntentFilter().apply {
            addAction(AegisAccessibilityService.ACTION_SHIELD_STATE)
            addAction(AegisAccessibilityService.ACTION_KILL_SWITCH)
        }
        ContextCompat.registerReceiver(this, stateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun safeInsetsPx(): IntArray {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.displayCutout() or WindowInsets.Type.systemBars()
            )
            return intArrayOf(insets.left, insets.top, insets.right, insets.bottom)
        }
        return intArrayOf(dp(8), dp(36), dp(8), dp(24))
    }

    private fun screenSizePx(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.currentWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            Pair(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        }
    }

    private fun clampOrbToSafeZone(params: WindowManager.LayoutParams) {
        val (width, height) = screenSizePx()
        val insets = safeInsetsPx()
        val orbPx = dp(56)
        val margin = dp(8)

        val minX = insets[0] + margin
        val maxX = (width - insets[2] - orbPx - margin).coerceAtLeast(minX)
        val minY = insets[1] + margin
        val maxY = (height - insets[3] - orbPx - margin).coerceAtLeast(minY)

        params.y = params.y.coerceIn(minY, maxY)
        params.x = if (params.x + orbPx / 2 < width / 2) minX else maxX
    }

    private fun attachFloatingOrb() {
        val orbPx = dp(56)
        val container = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = createOrbDrawable(Color.parseColor(AegisDefaults.ORB_ACCENT_HEX), 1f)
            elevation = dp(8).toFloat()
        }

        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_aegis_shield)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        }
        container.addView(icon)

        val params = WindowManager.LayoutParams(
            orbPx,
            orbPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(180)
        }
        clampOrbToSafeZone(params)

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    windowManager.updateViewLayout(container, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        toggleCommandPill()
                    } else {
                        clampOrbToSafeZone(params)
                        windowManager.updateViewLayout(container, params)
                    }
                    true
                }
                else -> false
            }
        }

        orbView = container
        orbParams = params
        windowManager.addView(container, params)
    }

    private fun createOrbDrawable(ringColor: Int, alphaFraction: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#131A2A"))
            setStroke(dp(3), ringColor)
            alpha = (alphaFraction * 255).toInt().coerceIn(80, 255)
        }
    }

    private fun setOrbVisualState(state: OrbVisualState) {
        currentVisualState = state
        mainHandler.post {
            val ringColor = when (state) {
                OrbVisualState.IDLE -> Color.parseColor(AegisDefaults.ORB_ACCENT_HEX)
                OrbVisualState.LOCAL_REFLEX -> Color.parseColor("#38BDF8")
                OrbVisualState.CLOUD_CONSULTANT -> Color.parseColor("#A78BFA")
                OrbVisualState.HITL_PAUSED -> Color.parseColor("#F59E0B")
                OrbVisualState.SHIELDED -> Color.parseColor("#64748B")
                OrbVisualState.KILLED -> Color.parseColor("#EF4444")
            }
            val opacity = if (state == OrbVisualState.SHIELDED) 0.45f else 0.96f
            orbView?.background = createOrbDrawable(ringColor, opacity)
        }
    }

    private fun toggleCommandPill() {
        if (currentVisualState == OrbVisualState.SHIELDED) {
            return
        }
        if (commandPillView != null) {
            collapseCommandPill()
            return
        }
        showCommandPill()
    }

    private fun collapseCommandPill() {
        removeViewSafe(commandPillView)
        commandPillView = null
    }

    private fun showCommandPill() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#131A2A"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#334155"))
            }
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "Aegis Command Pill"
            setTextColor(Color.parseColor("#F1F5F9"))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val modeBtn = Button(this).apply {
            text = if (useCloudMode) "Cloud" else "Local"
            textSize = 11f
            isAllCaps = false
            setTextColor(Color.parseColor("#0B0F19"))
            setBackgroundColor(Color.parseColor(if (useCloudMode) "#A78BFA" else "#38BDF8"))
            setOnClickListener {
                useCloudMode = !useCloudMode
                text = if (useCloudMode) "Cloud" else "Local"
                setBackgroundColor(Color.parseColor(if (useCloudMode) "#A78BFA" else "#38BDF8"))
            }
        }
        modeToggleBtn = modeBtn

        val stopBtn = Button(this).apply {
            text = "STOP"
            textSize = 11f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#EF4444"))
            setOnClickListener {
                AegisAccessibilityService.instance?.abortAllRuns("User tapped red STOP button in Command Pill")
                dismissHitlCard()
                setOrbVisualState(OrbVisualState.KILLED)
                updateStatusLabel("STOPPED · Execution aborted")
            }
        }

        headerRow.addView(title)
        headerRow.addView(modeBtn)
        headerRow.addView(stopBtn)
        card.addView(headerRow)

        val statusTv = TextView(this).apply {
            text = "Ready · PolicyGatekeeper armed"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, dp(6), 0, dp(6))
        }
        statusTextView = statusTv
        card.addView(statusTv)

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val input = EditText(this).apply {
            hint = "Tell Aegis what to do..."
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        goalInputView = input

        val micBtn = Button(this).apply {
            text = "Mic"
            textSize = 11f
            isAllCaps = false
            setTextColor(Color.parseColor("#F8FAFC"))
            setBackgroundColor(Color.parseColor("#1E293B"))
            setOnClickListener { startVoiceInput() }
        }

        val runBtn = Button(this).apply {
            text = "Run"
            textSize = 11f
            isAllCaps = false
            setTextColor(Color.parseColor("#0B0F19"))
            setBackgroundColor(Color.parseColor("#10B981"))
            setOnClickListener {
                val goal = input.text?.toString()?.trim().orEmpty()
                if (goal.isNotEmpty()) {
                    executeAgentGoal(goal)
                }
            }
        }

        inputRow.addView(input)
        inputRow.addView(micBtn)
        inputRow.addView(runBtn)
        card.addView(inputRow)

        val params = WindowManager.LayoutParams(
            dp(320),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = safeInsetsPx()[1] + dp(16)
        }

        commandPillView = card
        windowManager.addView(card, params)
    }

    private fun startVoiceInput() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateStatusLabel("Voice input unavailable on this device")
            return
        }
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    updateStatusLabel("Listening via OS SpeechRecognizer...")
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val phrase = matches?.firstOrNull().orEmpty()
                    if (phrase.isNotBlank()) {
                        goalInputView?.setText(phrase)
                        updateStatusLabel("Captured: $phrase")
                    }
                }
                override fun onError(error: Int) {
                    updateStatusLabel("SpeechRecognizer error code $error")
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun executeAgentGoal(goal: String) {
        AegisAccessibilityService.resetKillSwitch()
        val bridge = AegisAccessibilityService.instance
        if (bridge == null) {
            updateStatusLabel("Enable Aegis Accessibility Service first")
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val localUrl = prefs.getString("local_server_url", AegisDefaults.LOCAL_SERVER_URL)
            ?: AegisDefaults.LOCAL_SERVER_URL
        val localModel = prefs.getString("local_model_id", AegisDefaults.LOCAL_MODEL_ID)
            ?: AegisDefaults.LOCAL_MODEL_ID
        val geminiModel = prefs.getString("gemini_model", AegisDefaults.GEMINI_MODEL)
            ?: AegisDefaults.GEMINI_MODEL
        val geminiKey = prefs.getString("gemini_api_key", "") ?: ""
        val thinkingLevel = prefs.getString("thinking_level", AegisDefaults.DEFAULT_THINKING_LEVEL)
            ?: AegisDefaults.DEFAULT_THINKING_LEVEL
        val enableGrounding = prefs.getBoolean("enable_grounding", AegisDefaults.ENABLE_SEARCH_GROUNDING)
        val strictHitl = prefs.getBoolean("strict_hitl_external", AegisDefaults.STRICT_HITL_EXTERNAL)
        val customBlacklist = PolicyGatekeeper.loadCustomBlacklist(applicationContext)

        setOrbVisualState(if (useCloudMode) OrbVisualState.CLOUD_CONSULTANT else OrbVisualState.LOCAL_REFLEX)
        updateStatusLabel("Step 1/5 · Capturing sanitized UI tree...")

        executor.execute {
            val snapshot = bridge.captureCompactUiTree()
            if (snapshot == null) {
                setOrbVisualState(OrbVisualState.SHIELDED)
                updateStatusLabel("SHIELDED · Foreground app is blacklisted")
                return@execute
            }

            if (snapshot.strippedInvisibleChars > 0) {
                store.appendAudit(
                    packageName = snapshot.packageName,
                    actionType = "SANITIZE",
                    brainTier = BrainTier.SYSTEM,
                    outcome = "INJECTION_STRIPPED",
                    details = "Stripped ${snapshot.strippedInvisibleChars} invisible Unicode chars"
                )
            }

            val scope = TaskScope(
                goal = goal,
                originPackage = snapshot.packageName,
                allowedPackages = setOf(snapshot.packageName)
            )

            // Tier 0 deterministic macro fast-path
            val matchedMacro = store.matchApprovedMacro(goal, snapshot.packageName)
            if (matchedMacro != null && matchedMacro.steps.isNotEmpty()) {
                val firstStep = matchedMacro.steps.first()
                val macroAction = ProposedAction(
                    actionType = firstStep.actionType,
                    targetPackage = firstStep.targetPackage,
                    targetNodeId = firstStep.targetNodeId,
                    targetLabel = firstStep.targetLabel,
                    textPayload = firstStep.textPayload,
                    rationale = "Tier 0 macro: ${matchedMacro.name}",
                    brainTier = BrainTier.TIER_0_MACRO
                )
                evaluateAndDispatch(macroAction, scope, snapshot.sawUntrustedContent, customBlacklist, strictHitl)
                return@execute
            }

            updateStatusLabel(if (useCloudMode) "Step 2/5 · Consulting Gemini..." else "Step 2/5 · Querying Local Reflex...")
            val result = if (useCloudMode) {
                brainClient.requestCloudConsultant(
                    apiKey = geminiKey,
                    modelName = geminiModel,
                    thinkingLevel = thinkingLevel,
                    enableSearchGrounding = enableGrounding,
                    goal = goal,
                    snapshot = snapshot
                )
            } else {
                brainClient.requestLocalReflex(localUrl, localModel, goal, snapshot)
            }

            val proposed = result.action
            if (!result.success || proposed == null) {
                setOrbVisualState(OrbVisualState.IDLE)
                updateStatusLabel("Brain error: ${result.errorMessage}")
                return@execute
            }

            evaluateAndDispatch(proposed, scope, snapshot.sawUntrustedContent, customBlacklist, strictHitl)
        }
    }

    private fun evaluateAndDispatch(
        action: ProposedAction,
        scope: TaskScope,
        sawUntrustedContent: Boolean,
        customBlacklist: Set<String>,
        strictHitl: Boolean
    ) {
        val decision = PolicyGatekeeper.evaluate(
            action = action,
            scope = scope,
            sawUntrustedContent = sawUntrustedContent,
            customBlacklist = customBlacklist,
            strictHitlExternal = strictHitl
        )

        when (decision.verdict) {
            PolicyVerdict.ALLOW -> {
                val ok = AegisAccessibilityService.instance?.executeAction(action) == true
                store.appendAudit(
                    packageName = action.targetPackage,
                    actionType = action.actionType.name,
                    brainTier = action.brainTier,
                    outcome = "ALLOWED",
                    details = "${decision.matchedRule}: ${action.targetLabel} (executed=$ok)"
                )
                setOrbVisualState(OrbVisualState.IDLE)
                updateStatusLabel("Executed (${decision.riskTier.name}): ${action.targetLabel}")
            }
            PolicyVerdict.REQUIRE_HITL -> {
                pendingHitlAction = action
                store.appendAudit(
                    packageName = action.targetPackage,
                    actionType = action.actionType.name,
                    brainTier = action.brainTier,
                    outcome = "HITL_PENDING",
                    details = "Frozen for human approval: ${action.targetLabel}"
                )
                setOrbVisualState(OrbVisualState.HITL_PAUSED)
                updateStatusLabel("HITL PAUSED · Confirm action on overlay card")
                mainHandler.post { showHitlConfirmationCard(action, decision) }
            }
            PolicyVerdict.BLOCKED_BLACKLIST,
            PolicyVerdict.BLOCKED_SCOPE,
            PolicyVerdict.BLOCKED_RULE_OF_TWO -> {
                store.appendAudit(
                    packageName = action.targetPackage,
                    actionType = action.actionType.name,
                    brainTier = action.brainTier,
                    outcome = decision.verdict.name,
                    details = decision.reason
                )
                setOrbVisualState(OrbVisualState.KILLED)
                updateStatusLabel("${decision.verdict.name}: ${decision.reason}")
            }
        }
    }

    private fun showHitlConfirmationCard(action: ProposedAction, decision: PolicyDecision) {
        dismissHitlCard()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#131A2A"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(2), Color.parseColor("#F59E0B"))
            }
        }

        val heading = TextView(this).apply {
            text = "HITL Confirmation Required"
            setTextColor(Color.parseColor("#F59E0B"))
            textSize = 15f
        }
        val details = TextView(this).apply {
            text = "Package: ${action.targetPackage}\n" +
                "Action: ${action.actionType.name} (${decision.riskTier.name})\n" +
                "Target Node: ${action.targetNodeId.ifBlank { action.targetLabel }}\n" +
                "Payload: ${action.textPayload.ifBlank { "(none)" }}"
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 12f
            setPadding(0, dp(8), 0, dp(12))
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val denyBtn = Button(this).apply {
            text = "DENY"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#EF4444"))
            setOnClickListener {
                pendingHitlAction = null
                store.appendAudit(
                    packageName = action.targetPackage,
                    actionType = action.actionType.name,
                    brainTier = action.brainTier,
                    outcome = "HITL_DENIED",
                    details = "User denied consequential action on ${action.targetLabel}"
                )
                dismissHitlCard()
                setOrbVisualState(OrbVisualState.IDLE)
                updateStatusLabel("Action denied by user")
            }
        }

        val approveBtn = Button(this).apply {
            text = "APPROVE ONCE"
            isAllCaps = false
            setTextColor(Color.parseColor("#0B0F19"))
            setBackgroundColor(Color.parseColor("#10B981"))
            setOnClickListener {
                val approved = pendingHitlAction
                pendingHitlAction = null
                dismissHitlCard()
                if (approved != null) {
                    executor.execute {
                        val ok = AegisAccessibilityService.instance?.executeAction(approved) == true
                        store.appendAudit(
                            packageName = approved.targetPackage,
                            actionType = approved.actionType.name,
                            brainTier = approved.brainTier,
                            outcome = "HITL_APPROVED",
                            details = "User approved once: ${approved.targetLabel} (executed=$ok)"
                        )
                        setOrbVisualState(OrbVisualState.IDLE)
                        updateStatusLabel("Approved & executed: ${approved.targetLabel}")
                    }
                }
            }
        }

        buttonRow.addView(denyBtn)
        buttonRow.addView(approveBtn)

        card.addView(heading)
        card.addView(details)
        card.addView(buttonRow)

        val params = WindowManager.LayoutParams(
            dp(320),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        hitlCardView = card
        windowManager.addView(card, params)
    }

    private fun dismissHitlCard() {
        mainHandler.post {
            removeViewSafe(hitlCardView)
            hitlCardView = null
        }
    }

    private fun updateStatusLabel(message: String) {
        mainHandler.post {
            statusTextView?.text = message
        }
    }

    private fun removeViewSafe(view: View?) {
        if (view != null) {
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
            }
        }
    }
}
