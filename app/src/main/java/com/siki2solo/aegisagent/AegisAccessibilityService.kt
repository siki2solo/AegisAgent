package com.siki2solo.aegisagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.security.MessageDigest

class AegisAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_SHIELD_STATE = "com.siki2solo.aegisagent.ACTION_SHIELD_STATE"
        const val ACTION_KILL_SWITCH = "com.siki2solo.aegisagent.ACTION_KILL_SWITCH"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_SHIELDED = "extra_shielded"

        private const val MAX_TREE_DEPTH = 12
        private const val MAX_TREE_NODES = 60
        private const val KILL_DOUBLE_PRESS_WINDOW_MS = 500L

        @Volatile
        var instance: AegisAccessibilityService? = null
            private set

        @Volatile
        var currentForegroundPackage: String = ""
            private set

        @Volatile
        var killSwitchActive: Boolean = false
            private set

        fun resetKillSwitch() {
            killSwitchActive = false
        }
    }

    private var lastVolumeDownPressMs: Long = 0L
    private lateinit var store: MacroAndAuditStore

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        store = MacroAndAuditStore(applicationContext)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {
        abortAllRuns("Accessibility service interrupted by system")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkgName = event.packageName?.toString()?.trim().orEmpty()
            if (pkgName.isEmpty() || pkgName == packageName) return
            currentForegroundPackage = pkgName

            val customBlacklist = PolicyGatekeeper.loadCustomBlacklist(applicationContext)
            val shielded = PolicyGatekeeper.isBlacklisted(pkgName, customBlacklist)

            val notifyIntent = Intent(ACTION_SHIELD_STATE).apply {
                setPackage(packageName)
                putExtra(EXTRA_PACKAGE_NAME, pkgName)
                putExtra(EXTRA_SHIELDED, shielded)
            }
            sendBroadcast(notifyIntent)

            if (shielded) {
                store.appendAudit(
                    packageName = pkgName,
                    actionType = "WINDOW_GUARD",
                    brainTier = BrainTier.SYSTEM,
                    outcome = "BLOCKED_BLACKLIST",
                    details = "Entered blacklisted package $pkgName. Screen tree capture halted."
                )
            }
        }
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event != null && event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.action == KeyEvent.ACTION_DOWN) {
            val now = System.currentTimeMillis()
            if (now - lastVolumeDownPressMs <= KILL_DOUBLE_PRESS_WINDOW_MS) {
                lastVolumeDownPressMs = 0L
                abortAllRuns("Hardware Volume-Down double press within 500ms")
                return true
            }
            lastVolumeDownPressMs = now
        }
        return super.onKeyEvent(event)
    }

    fun abortAllRuns(reason: String = "Kill Switch triggered") {
        killSwitchActive = true
        val pkgName = currentForegroundPackage.ifBlank { packageName }
        if (::store.isInitialized) {
            store.appendAudit(
                packageName = pkgName,
                actionType = "KILL_SWITCH",
                brainTier = BrainTier.SYSTEM,
                outcome = "KILL_SWITCH_TRIGGERED",
                details = reason
            )
        }
        val killIntent = Intent(ACTION_KILL_SWITCH).apply {
            setPackage(packageName)
        }
        sendBroadcast(killIntent)
    }

    fun captureCompactUiTree(): ScreenSnapshot? {
        val activePkg = currentForegroundPackage.ifBlank {
            rootInActiveWindow?.packageName?.toString().orEmpty()
        }
        val customBlacklist = PolicyGatekeeper.loadCustomBlacklist(applicationContext)
        if (activePkg.isNotEmpty() && PolicyGatekeeper.isBlacklisted(activePkg, customBlacklist)) {
            return null
        }

        val root = rootInActiveWindow ?: return null
        val lines = mutableListOf<String>()
        var totalStripped = 0
        var detectedUntrusted = activePkg.contains("chrome", ignoreCase = true) ||
            activePkg.contains("browser", ignoreCase = true) ||
            activePkg.contains("webview", ignoreCase = true)

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_TREE_DEPTH || lines.size >= MAX_TREE_NODES) return

            val rawText = node.text?.toString().orEmpty()
            val rawDesc = node.contentDescription?.toString().orEmpty()
            val labelRaw = if (rawText.isNotBlank()) rawText else rawDesc
            val sanitized = PolicyGatekeeper.sanitizeText(labelRaw)
            totalStripped += sanitized.strippedCount
            if (sanitized.detectedInjection) {
                detectedUntrusted = true
            }

            if (node.isClickable || node.isEditable || sanitized.cleanText.isNotBlank()) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val cx = rect.centerX()
                val cy = rect.centerY()
                val viewId = node.viewIdResourceName?.substringAfterLast('/') ?: "node"
                val cls = node.className?.toString()?.substringAfterLast('.') ?: "View"
                val safeLabel = sanitized.cleanText.replace("\n", " ").take(80)
                lines.add("[$viewId] $cls \"$safeLabel\" ($cx, $cy)")
            }

            for (i in 0 until node.childCount) {
                if (lines.size >= MAX_TREE_NODES) break
                walk(node.getChild(i), depth + 1)
            }
        }

        walk(root, 0)
        val compactTree = lines.joinToString("\n")
        return ScreenSnapshot(
            packageName = activePkg,
            compactTree = compactTree,
            sha256 = sha256Hex(compactTree),
            strippedInvisibleChars = totalStripped,
            sawUntrustedContent = detectedUntrusted,
            capturedAtMs = System.currentTimeMillis()
        )
    }

    fun executeAction(action: ProposedAction): Boolean {
        if (killSwitchActive) return false
        return when (action.actionType) {
            ActionType.TAP -> {
                val targetPoint = resolveTargetCoordinates(action)
                dispatchTap(targetPoint.first, targetPoint.second)
            }
            ActionType.SWIPE -> {
                val sx = if (action.x > 0f) action.x else 540f
                val sy = if (action.y > 0f) action.y else 1400f
                val ex = if (action.endX > 0f) action.endX else 540f
                val ey = if (action.endY > 0f) action.endY else 600f
                dispatchSwipe(sx, sy, ex, ey)
            }
            ActionType.TYPE_TEXT -> setTextOnTarget(action)
            ActionType.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            ActionType.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            ActionType.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            ActionType.LAUNCH_APP -> {
                val launch = packageManager.getLaunchIntentForPackage(action.targetPackage)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launch)
                    true
                } else {
                    false
                }
            }
            ActionType.DONE -> true
        }
    }

    private fun resolveTargetCoordinates(action: ProposedAction): Pair<Float, Float> {
        if (action.x > 0f && action.y > 0f) {
            return Pair(action.x, action.y)
        }
        val root = rootInActiveWindow ?: return Pair(540f, 960f)
        val node = findNodeByIdOrLabel(root, action.targetNodeId, action.targetLabel)
        if (node != null) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            return Pair(rect.centerX().toFloat(), rect.centerY().toFloat())
        }
        return Pair(540f, 960f)
    }

    private fun setTextOnTarget(action: ProposedAction): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findNodeByIdOrLabel(root, action.targetNodeId, action.targetLabel)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.textPayload)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findNodeByIdOrLabel(
        root: AccessibilityNodeInfo,
        nodeId: String,
        label: String
    ): AccessibilityNodeInfo? {
        if (nodeId.isNotBlank()) {
            val byId = root.findAccessibilityNodeInfosByViewId(nodeId)
            if (!byId.isNullOrEmpty()) return byId.first()
        }
        if (label.isNotBlank()) {
            val byText = root.findAccessibilityNodeInfosByText(label)
            if (!byText.isNullOrEmpty()) return byText.first()
        }
        return null
    }

    private fun dispatchTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun dispatchSwipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 280L)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
