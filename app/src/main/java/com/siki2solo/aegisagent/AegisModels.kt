package com.siki2solo.aegisagent

enum class BrainTier {
    TIER_0_MACRO,
    TIER_1_LOCAL,
    TIER_2_CLOUD,
    SYSTEM
}

enum class LocalEngineKind {
    NONE,
    LITERT_LM,
    LLAMA_GGUF
}

enum class RiskTier {
    READ_ONLY,
    LOCAL_REVERSIBLE,
    CONSEQUENTIAL
}

enum class ActionType {
    TAP,
    SWIPE,
    TYPE_TEXT,
    BACK,
    HOME,
    RECENTS,
    LAUNCH_APP,
    DONE
}

enum class PolicyVerdict {
    ALLOW,
    REQUIRE_HITL,
    BLOCKED_BLACKLIST,
    BLOCKED_SCOPE,
    BLOCKED_RULE_OF_TWO
}

enum class OrbVisualState {
    IDLE,
    LOCAL_REFLEX,
    CLOUD_CONSULTANT,
    HITL_PAUSED,
    SHIELDED,
    KILLED
}

data class CuratedModelSpec(
    val id: String,
    val fileName: String,
    val displayName: String,
    val engineKind: LocalEngineKind,
    val badge: String,
    val downloadUrl: String,
    val note: String = ""
)

data class DownloadProgressInfo(
    val status: Int,
    val percent: Int,
    val downloadedMb: Double,
    val totalMb: Double
)

data class LocalInferenceOutput(
    val success: Boolean,
    val text: String = "",
    val tokensPerSecond: Double = 0.0,
    val latencyMs: Long = 0L,
    val engineLabel: String = "",
    val errorMessage: String = ""
)

data class SanitizeResult(
    val cleanText: String,
    val strippedCount: Int,
    val detectedInjection: Boolean
)

data class TaskScope(
    val goal: String,
    val originPackage: String,
    val allowedPackages: Set<String>,
    val allowExternalSend: Boolean = false
)

data class ProposedAction(
    val actionType: ActionType,
    val targetPackage: String,
    val targetNodeId: String = "",
    val targetLabel: String = "",
    val textPayload: String = "",
    val x: Float = 0f,
    val y: Float = 0f,
    val endX: Float = 0f,
    val endY: Float = 0f,
    val rationale: String = "",
    val answerText: String = "",
    val needsCloudEscalation: Boolean = false,
    val brainTier: BrainTier = BrainTier.TIER_1_LOCAL
)

data class PolicyDecision(
    val verdict: PolicyVerdict,
    val riskTier: RiskTier,
    val reason: String,
    val matchedRule: String
)

data class ScreenSnapshot(
    val packageName: String,
    val compactTree: String,
    val sha256: String,
    val strippedInvisibleChars: Int,
    val sawUntrustedContent: Boolean,
    val capturedAtMs: Long
)

data class MacroStep(
    val actionType: ActionType,
    val targetPackage: String,
    val targetNodeId: String,
    val targetLabel: String,
    val textPayload: String
)

data class SavedMacro(
    val id: String,
    val name: String,
    val triggerPhrase: String,
    val targetPackage: String,
    val createdAt: String,
    val approvedByUser: Boolean,
    val enabled: Boolean = true,
    val isStarter: Boolean = false,
    val steps: List<MacroStep>
)

data class AuditRecord(
    val timestampIso: String,
    val packageName: String,
    val actionType: String,
    val brainTier: String,
    val outcome: String,
    val details: String
)

data class BrainResult(
    val success: Boolean,
    val action: ProposedAction? = null,
    val rawResponse: String = "",
    val errorMessage: String = "",
    val latencyMs: Long = 0L,
    val engineSummary: String = "",
    val tokensPerSecond: Double = 0.0
)

object AegisDefaults {
    const val LITERT_GPU_AUTO = true
    const val ENABLE_TERMUX_FALLBACK = false
    const val LOCAL_SERVER_URL = "http://127.0.0.1:8080/v1/chat/completions"
    const val LOCAL_MODEL_ID = "LiquidAI/LFM3-1.7B-GGUF:Q4_K_M"
    const val GEMINI_MODEL = "gemini-4-hydrogen"
    const val DEFAULT_THINKING_LEVEL = "medium"
    const val ENABLE_SEARCH_GROUNDING = false
    const val STRICT_HITL_EXTERNAL = true
    const val ORB_ACCENT_HEX = "#38BDF8"
    val DEFAULT_CUSTOM_BLACKLIST: Set<String> = setOf("com.whatsapp.w4b")
    const val SEED_MACROS_JSON = "[{\"id\":\"starter-summarize-screen\",\"name\":\"Summarize This Screen\",\"triggerPhrase\":\"Summarize This Screen\",\"targetPackage\":\"*\",\"createdAt\":\"2026-11-27T09:00:00Z\",\"approvedByUser\":true,\"enabled\":true,\"isStarter\":true,\"steps\":[{\"actionType\":\"DONE\",\"targetPackage\":\"*\",\"targetNodeId\":\"aegis/live_response_box\",\"targetLabel\":\"Live Summary Response\",\"textPayload\":\"Read-only summary displayed inside floating Command Pill Live Response Box.\"}]},{\"id\":\"starter-extract-key-points\",\"name\":\"Extract Key Points\",\"triggerPhrase\":\"Extract Key Points\",\"targetPackage\":\"*\",\"createdAt\":\"2026-11-27T09:00:00Z\",\"approvedByUser\":true,\"enabled\":true,\"isStarter\":true,\"steps\":[{\"actionType\":\"DONE\",\"targetPackage\":\"*\",\"targetNodeId\":\"aegis/live_response_box\",\"targetLabel\":\"Key Points Response\",\"textPayload\":\"Bullet points extracted locally and copied into Live Response Box.\"}]},{\"id\":\"starter-go-back\",\"name\":\"Go Back\",\"triggerPhrase\":\"Go Back\",\"targetPackage\":\"*\",\"createdAt\":\"2026-11-27T09:00:00Z\",\"approvedByUser\":true,\"enabled\":true,\"isStarter\":true,\"steps\":[{\"actionType\":\"BACK\",\"targetPackage\":\"*\",\"targetNodeId\":\"system/back\",\"targetLabel\":\"Global Back Navigation\",\"textPayload\":\"\"}]},{\"id\":\"macro-notes-checklist\",\"name\":\"Save study checklist to Notes\",\"triggerPhrase\":\"Save study checklist to Notes\",\"targetPackage\":\"com.google.android.keep\",\"createdAt\":\"2026-11-27T09:00:00Z\",\"approvedByUser\":true,\"enabled\":true,\"isStarter\":false,\"steps\":[{\"actionType\":\"TYPE_TEXT\",\"targetPackage\":\"com.google.android.keep\",\"targetNodeId\":\"com.google.android.keep:id/edit_note_text\",\"targetLabel\":\"Note body editor\",\"textPayload\":\"1. Revise OS virtual memory\\n2. Solve 3 graph problems\\n3. Review SQLite indexing\"},{\"actionType\":\"TAP\",\"targetPackage\":\"com.google.android.keep\",\"targetNodeId\":\"com.google.android.keep:id/save_note_btn\",\"targetLabel\":\"Save Note\",\"textPayload\":\"\"}]}]"
}
