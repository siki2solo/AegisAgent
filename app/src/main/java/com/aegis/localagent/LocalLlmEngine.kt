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
    val configuredModelPath: String = "/data/local/tmp/llm/gemma-3-1b-it-int4.task"
    private var engine: LlmInference? = null

    fun isModelFilePresent(): Boolean {
        return File(configuredModelPath).exists()
    }

    fun ensureLoaded(): Result<Unit> {
        if (engine != null) return Result.success(Unit)
        val modelFile = File(configuredModelPath)
        if (!modelFile.exists()) {
            return Result.failure(
                IllegalStateException("Local model missing at $configuredModelPath")
            )
        }
        return runCatching {
            val opts = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(512)
                .setTemperature(0.2f)
                .setTopK(32)
                .build()
            engine = LlmInference.createFromOptions(context, opts)
        }
    }

    fun planNextAction(goal: String, sanitizedNodesJson: String): PlannedUiAction {
        val activeEngine = engine
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
            append("You are an offline Android accessibility planner. ")
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
        engine?.close()
        engine = null
    }
}
