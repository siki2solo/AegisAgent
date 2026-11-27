package com.aegis.localagent

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

data class CapturedScreenSnapshot(
    val packageName: String,
    val nodesJson: String,
    val visibleCount: Int,
    val skippedProtectedCount: Int,
    val strippedUnicodeCount: Int
)

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        @Volatile
        var activeAbortFlag: Boolean = false

        val allowedPackages: MutableSet<String> = mutableSetOf("com.android.settings", "com.example.messages", "com.example.notes", "com.example.transit", "com.example.planner", "com.android.chrome")
    }

    private val nodeLookup = mutableMapOf<String, AccessibilityNodeInfo>()
    private val gatekeeper = PolicyGatekeeper()
    private var lastVolDownTimestampMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        activeAbortFlag = false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (gatekeeper.isBlacklisted(pkg) || !allowedPackages.contains(pkg)) {
            return
        }
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event != null &&
            event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        ) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastVolDownTimestampMs <= 650L) {
                activeAbortFlag = true
                clearLookup()
                lastVolDownTimestampMs = 0L
                return true
            }
            lastVolDownTimestampMs = now
        }
        return super.onKeyEvent(event)
    }

    override fun onInterrupt() {
        activeAbortFlag = true
        clearLookup()
    }

    override fun onDestroy() {
        clearLookup()
        instance = null
        super.onDestroy()
    }

    fun captureAllowlistedTree(): CapturedScreenSnapshot? {
        if (activeAbortFlag) return null
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString().orEmpty()
        if (gatekeeper.isBlacklisted(pkg) || !allowedPackages.contains(pkg)) {
            return null
        }

        clearLookup()
        val array = JSONArray()
        var skipped = 0
        var strippedTotal = 0
        var idx = 0

        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null || activeAbortFlag) return
            val isPasswordType = isPasswordInput(node.inputType)
            if (node.isPassword || isPasswordType) {
                skipped += 1
                return
            }

            val rawLabel = node.text?.toString().orEmpty()
            val rawDesc = node.contentDescription?.toString().orEmpty()
            val (label, labelStripped) = if (gatekeeper.stripUnicodeEnabled) {
                InputSanitizer.stripInvisibleUnicode(rawLabel)
            } else {
                rawLabel to 0
            }
            val (desc, descStripped) = if (gatekeeper.stripUnicodeEnabled) {
                InputSanitizer.stripInvisibleUnicode(rawDesc)
            } else {
                rawDesc to 0
            }
            strippedTotal += labelStripped + descStripped

            if (node.isClickable || node.isEditable || label.isNotBlank() || desc.isNotBlank()) {
                val nid = "n_${idx++}"
                nodeLookup[nid] = node
                array.put(
                    JSONObject().apply {
                        put("id", nid)
                        put("rid", node.viewIdResourceName ?: "")
                        put("text", label)
                        put("desc", desc)
                        put("clickable", node.isClickable)
                        put("editable", node.isEditable)
                    }
                )
            }
            for (i in 0 until node.childCount) {
                visit(node.getChild(i))
            }
        }

        visit(root)
        return CapturedScreenSnapshot(
            packageName = pkg,
            nodesJson = array.toString(),
            visibleCount = array.length(),
            skippedProtectedCount = skipped,
            strippedUnicodeCount = strippedTotal
        )
    }

    fun dispatchUiAction(action: PlannedUiAction): Boolean {
        if (activeAbortFlag) return false
        return when (action.action) {
            "tap" -> {
                val target = nodeLookup[action.nodeId] ?: return false
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            "type" -> {
                val target = nodeLookup[action.nodeId] ?: return false
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        action.textInput
                    )
                }
                target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            else -> false
        }
    }

    private fun isPasswordInput(inputType: Int): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun clearLookup() {
        nodeLookup.clear()
    }
}
