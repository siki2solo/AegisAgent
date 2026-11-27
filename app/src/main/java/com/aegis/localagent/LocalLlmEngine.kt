package com.aegis.localagent

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import org.json.JSONObject
import java.io.File

data class PlannedUiAction(
    val action: String,
    val nodeId: String,
    val textInput: String,
    val requiresApproval: Boolean,
    val reason: String
)

class LocalLlmEngine(private val context: Context) {

    val configuredModelName: String = "Gemma 3 1B Instruct"
    val primaryModelPath: String = "/data/local/tmp/llm/gemma-3-1b-it-int4.task"
    val secondaryModelPath: String = "/data/local/tmp/llm/gemma-3n-e2b-it-int4.task"
    val autoRoutingEnabled: Boolean = true

    private var primaryEngine: LlmInference? = null
    private var secondaryEngine: LlmInference? = null

    fun isModelFilePresent(): Boolean {
        return File(primaryModelPath).exists() || File(secondaryModelPath).exists()
    }

    fun classifyComplexityLevel(goal: String, hasImageAttachment: Boolean): Int {
        val lower = goal.lowercase()
        if (lower.contains("mcp") || lower.contains("traceback") || lower.contains("patch")) {
            return 3
        }
        if (hasImageAttachment || lower.contains("pdf") || lower.contains("rag") || lower.contains("script")) {
            return 2
        }
        return 1
    }

    fun ensureLoadedForLevel(level: Int): Result<String> {
        val useSecondary = autoRoutingEnabled && level >= 2 && File(secondaryModelPath).exists()
        val chosenPath = if (useSecondary) secondaryModelPath else primaryModelPath
        val existing = if (useSecondary) secondaryEngine else primaryEngine
        if (existing != null) return Result.success(chosenPath)

        val modelFile = File(chosenPath)
        if (!modelFile.exists()) {
            return Result.failure(
                IllegalStateException("Local model missing at $chosenPath")
            )
        }
        return runCatching {
            val opts = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(768)
                .setTemperature(0.2f)
                .setTopK(32)
                .build()
            val instance = LlmInference.createFromOptions(context, opts)
            if (useSecondary) {
                secondaryEngine = instance
            } else {
                primaryEngine = instance
            }
            chosenPath
        }
    }

    fun planNextAction(goal: String, sanitizedNodesJson: String, level: Int = 1): PlannedUiAction {
        val activeEngine = if (autoRoutingEnabled && level >= 2) {
            secondaryEngine ?: primaryEngine
        } else {
            primaryEngine
        }
        if (activeEngine == null) {
            return PlannedUiAction(
                action = "noop",
                nodeId = "",
                textInput = "",
                requiresApproval = false,
                reason = "On-device model not initialized yet."
            )
        }

        val prompt = buildString {
            append("You are an offline Android Level ").append(level).append(" agent. ")
            append("Return ONLY one compact JSON object with keys: ")
            append("action (tap|type|back|done), nodeId, textInput, requiresApproval (boolean), reason.\n")
            append("Goal: ").append(goal).append("\n")
            append("VisibleNodes: ").append(sanitizedNodesJson)
        }

        val raw = activeEngine.generateResponse(prompt).trim()
        val jsonStart = raw.indexOf('{')
        val jsonEnd = raw.lastIndexOf('}')
        if (jsonStart < 0 || jsonEnd <= jsonStart) {
            return PlannedUiAction("noop", "", "", false, "Model returned non-JSON output")
        }

        return try {
            val obj = JSONObject(raw.substring(jsonStart, jsonEnd + 1))
            PlannedUiAction(
                action = obj.optString("action", "noop"),
                nodeId = obj.optString("nodeId", ""),
                textInput = obj.optString("textInput", ""),
                requiresApproval = obj.optBoolean("requiresApproval", true),
                reason = obj.optString("reason", "Planned locally")
            )
        } catch (_: Exception) {
            PlannedUiAction("noop", "", "", false, "Failed to parse JSON action")
        }
    }

    fun release() {
        primaryEngine?.close()
        secondaryEngine?.close()
        primaryEngine = null
        secondaryEngine = null
    }
}
