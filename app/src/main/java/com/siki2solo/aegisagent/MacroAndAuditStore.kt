package com.siki2solo.aegisagent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Append-only audit log (audit_log.jsonl) and user-approved macro store (macros.json).
 * By design, no delete or edit methods are exposed for audit_log.jsonl.
 */
class MacroAndAuditStore(private val context: Context) {

    private val auditFile: File
        get() = File(context.filesDir, "audit_log.jsonl")

    private val macrosFile: File
        get() = File(context.filesDir, "macros.json")

    private val loraExportFile: File
        get() = File(context.filesDir, "aegis_approved_lora.jsonl")

    init {
        ensureSeedMacros()
    }

    @Synchronized
    fun appendAudit(
        packageName: String,
        actionType: String,
        brainTier: BrainTier,
        outcome: String,
        details: String
    ) {
        val iso = currentIsoTimestamp()
        val row = JSONObject().apply {
            put("timestampIso", iso)
            put("packageName", packageName)
            put("actionType", actionType)
            put("brainTier", brainTier.name)
            put("outcome", outcome)
            put("details", details)
        }
        FileOutputStream(auditFile, true).use { fos ->
            fos.write((row.toString() + "\n").toByteArray(Charsets.UTF_8))
        }
    }

    @Synchronized
    fun readRecentAudit(limit: Int = 80): List<AuditRecord> {
        if (!auditFile.exists()) return emptyList()
        val lines = auditFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        return lines.takeLast(limit).reversed().mapNotNull { line ->
            try {
                val obj = JSONObject(line)
                AuditRecord(
                    timestampIso = obj.optString("timestampIso", ""),
                    packageName = obj.optString("packageName", ""),
                    actionType = obj.optString("actionType", ""),
                    brainTier = obj.optString("brainTier", "SYSTEM"),
                    outcome = obj.optString("outcome", ""),
                    details = obj.optString("details", "")
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    @Synchronized
    fun loadMacros(): List<SavedMacro> {
        ensureSeedMacros()
        if (!macrosFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(macrosFile.readText(Charsets.UTF_8))
            val list = mutableListOf<SavedMacro>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val stepsArr = obj.optJSONArray("steps") ?: JSONArray()
                val steps = mutableListOf<MacroStep>()
                for (s in 0 until stepsArr.length()) {
                    val stepObj = stepsArr.getJSONObject(s)
                    val actName = stepObj.optString("actionType", "TAP")
                    val actType = ActionType.entries.firstOrNull { it.name == actName } ?: ActionType.TAP
                    steps.add(
                        MacroStep(
                            actionType = actType,
                            targetPackage = stepObj.optString("targetPackage", ""),
                            targetNodeId = stepObj.optString("targetNodeId", ""),
                            targetLabel = stepObj.optString("targetLabel", ""),
                            textPayload = stepObj.optString("textPayload", "")
                        )
                    )
                }
                list.add(
                    SavedMacro(
                        id = obj.optString("id", "macro-$i"),
                        name = obj.optString("name", "Routine"),
                        triggerPhrase = obj.optString("triggerPhrase", ""),
                        targetPackage = obj.optString("targetPackage", ""),
                        createdAt = obj.optString("createdAt", ""),
                        approvedByUser = obj.optBoolean("approvedByUser", true),
                        steps = steps
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun saveMacro(macro: SavedMacro) {
        val current = loadMacros().filterNot { it.id == macro.id }.toMutableList()
        current.add(0, macro)
        val arr = JSONArray()
        for (item in current) {
            val stepsArr = JSONArray()
            for (step in item.steps) {
                stepsArr.put(
                    JSONObject().apply {
                        put("actionType", step.actionType.name)
                        put("targetPackage", step.targetPackage)
                        put("targetNodeId", step.targetNodeId)
                        put("targetLabel", step.targetLabel)
                        put("textPayload", step.textPayload)
                    }
                )
            }
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("name", item.name)
                    put("triggerPhrase", item.triggerPhrase)
                    put("targetPackage", item.targetPackage)
                    put("createdAt", item.createdAt)
                    put("approvedByUser", item.approvedByUser)
                    put("steps", stepsArr)
                }
            )
        }
        macrosFile.writeText(arr.toString(2), Charsets.UTF_8)
        appendAudit(
            packageName = macro.targetPackage,
            actionType = "MACRO_SAVE",
            brainTier = BrainTier.TIER_0_MACRO,
            outcome = "ROUTINE_SAVED",
            details = "Saved approved routine: ${macro.name}"
        )
    }

    fun matchApprovedMacro(goal: String, foregroundPackage: String): SavedMacro? {
        val normalized = goal.trim().lowercase(Locale.US)
        return loadMacros().firstOrNull { macro ->
            macro.approvedByUser &&
                macro.targetPackage == foregroundPackage &&
                (macro.triggerPhrase.trim().lowercase(Locale.US) == normalized ||
                    macro.name.trim().lowercase(Locale.US) == normalized)
        }
    }

    @Synchronized
    fun exportApprovedLoraJsonl(): File {
        val approved = loadMacros().filter { it.approvedByUser }
        val sb = StringBuilder()
        for (macro in approved) {
            val stepsJson = JSONArray()
            for (step in macro.steps) {
                stepsJson.put(
                    JSONObject().apply {
                        put("actionType", step.actionType.name)
                        put("targetPackage", step.targetPackage)
                        put("targetNodeId", step.targetNodeId)
                        put("targetLabel", step.targetLabel)
                        put("textPayload", step.textPayload)
                    }
                )
            }
            val messages = JSONArray().apply {
                put(
                    JSONObject()
                        .put("role", "system")
                        .put("content", "You are AegisAgent Local Reflex (LFM3-1.7B). Output strictly valid ProposedAction JSON.")
                )
                put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", "Goal: ${macro.triggerPhrase} | ForegroundPackage: ${macro.targetPackage}")
                )
                put(
                    JSONObject()
                        .put("role", "assistant")
                        .put(
                            "content",
                            JSONObject()
                                .put("routine", macro.name)
                                .put("steps", stepsJson)
                                .toString()
                        )
                )
            }
            val line = JSONObject().apply {
                put("schema", "aegis.lora.v1")
                put("approved", true)
                put("created_at", macro.createdAt)
                put("messages", messages)
            }
            sb.append(line.toString()).append("\n")
        }
        loraExportFile.writeText(sb.toString(), Charsets.UTF_8)
        return loraExportFile
    }

    private fun ensureSeedMacros() {
        if (!macrosFile.exists()) {
            macrosFile.writeText(AegisDefaults.SEED_MACROS_JSON, Charsets.UTF_8)
        }
    }

    private fun currentIsoTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }
}
