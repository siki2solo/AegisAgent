package com.aegis.localagent

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

data class CapturedScreenSnapshot(
    val packageName: String,
    val nodesJson: String,
    val visibleCount: Int,
    val skippedProtectedCount: Int
)

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        val allowedPackages: MutableSet<String> = mutableSetOf("com.android.settings", "com.example.messages", "com.example.notes", "com.example.transit")
    }

    private val nodeLookup = mutableMapOf<String, AccessibilityNodeInfo>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (!allowedPackages.contains(pkg)) {
            return
        }
    }

    override fun onInterrupt() {
        clearLookup()
    }

    override fun onDestroy() {
        clearLookup()
        instance = null
        super.onDestroy()
    }

    fun captureAllowlistedTree(): CapturedScreenSnapshot? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString().orEmpty()
        if (!allowedPackages.contains(pkg)) {
            return null
        }

        clearLookup()
        val array = JSONArray()
        var skipped = 0
        var idx = 0

        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val isPasswordType = isPasswordInput(node.inputType)
            if (node.isPassword || isPasswordType) {
                skipped += 1
                return
            }

            val label = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
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
            skippedProtectedCount = skipped
        )
    }

    fun dispatchUiAction(action: PlannedUiAction): Boolean {
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
