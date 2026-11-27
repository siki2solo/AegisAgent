package com.siki2solo.aegisagent

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 3-Tier Dual-Engine Hybrid Brain Router:
 *   Tier 0 -> MacroAndAuditStore (exact trigger or GGUF cosine-similarity match)
 *   Tier 1 -> Embedded Local Engine (LiteRtBrainEngine or LlamaGgufBrainEngine, or optional Termux fallback)
 *   Tier 2 -> Gemini Cloud Consultant fallback (when no local model loaded, local JSON fails twice, or needsCloudEscalation=true)
 */
class HybridBrainClient {

    private val systemPrompt =
        "You are AegisAgent. Respond ONLY with a single JSON object matching: " +
            "{\"actionType\":\"TAP|SWIPE|TYPE_TEXT|BACK|HOME|RECENTS|LAUNCH_APP|DONE\"," +
            "\"targetPackage\":\"<pkg>\",\"targetNodeId\":\"<id>\",\"targetLabel\":\"<label>\"," +
            "\"textPayload\":\"<text>\",\"answerText\":\"<concise user-facing answer for read-only tasks>\"," +
            "\"needsCloudEscalation\":false,\"x\":0,\"y\":0,\"rationale\":\"<short reason>\"}."

    fun routeAndPropose(
        goal: String,
        snapshot: ScreenSnapshot,
        store: MacroAndAuditStore,
        forceCloud: Boolean,
        enableTermuxFallback: Boolean,
        localServerUrl: String,
        localModelId: String,
        geminiApiKey: String,
        geminiModel: String,
        thinkingLevel: String,
        enableSearchGrounding: Boolean,
        onStatus: (String, OrbVisualState) -> Unit
    ): BrainResult {
        // Tier 0: Exact trigger or GGUF cosine-similarity macro fast-path
        val matchedMacro = store.matchApprovedMacro(
            goal = goal,
            foregroundPackage = snapshot.packageName,
            embedder = { text -> ModelManager.embedTextIfAvailable(text) }
        )

        if (matchedMacro != null && matchedMacro.steps.isNotEmpty()) {
            val firstStep = matchedMacro.steps.first()
            val resolvedPkg = if (firstStep.targetPackage == "*") snapshot.packageName else firstStep.targetPackage

            // Starter read-only screen analysis macros enrich the Live Response Box with local/cloud answer
            if (matchedMacro.isStarter && firstStep.actionType == ActionType.DONE) {
                val summaryPrompt = "$goal based ONLY on this screen text:\n${snapshot.compactTree}"
                val localSummary = if (!forceCloud && ModelManager.isAnyLocalEngineLoaded()) {
                    onStatus("Tier 0+1 · Summarizing locally (${ModelManager.activeModelFileName})...", OrbVisualState.LOCAL_REFLEX)
                    ModelManager.generateWithActiveEngine(
                        systemPrompt = "Provide a concise, factual summary in 3 short bullet points.",
                        userPrompt = summaryPrompt
                    )
                } else {
                    null
                }
                val answer = localSummary?.takeIf { it.success }?.text
                    ?: "Screen summary for ${snapshot.packageName}: ${snapshot.compactTree.lines().take(5).joinToString(" · ")}"

                return BrainResult(
                    success = true,
                    action = ProposedAction(
                        actionType = ActionType.DONE,
                        targetPackage = resolvedPkg,
                        targetNodeId = firstStep.targetNodeId,
                        targetLabel = matchedMacro.name,
                        textPayload = answer,
                        answerText = answer,
                        rationale = "Tier 0 starter macro: ${matchedMacro.name}",
                        brainTier = BrainTier.TIER_0_MACRO
                    ),
                    engineSummary = localSummary?.engineLabel?.ifBlank { "Tier 0 Macro" } ?: "Tier 0 Macro",
                    tokensPerSecond = localSummary?.tokensPerSecond ?: 0.0
                )
            }

            return BrainResult(
                success = true,
                action = ProposedAction(
                    actionType = firstStep.actionType,
                    targetPackage = resolvedPkg,
                    targetNodeId = firstStep.targetNodeId,
                    targetLabel = firstStep.targetLabel,
                    textPayload = firstStep.textPayload,
                    answerText = firstStep.textPayload,
                    rationale = "Tier 0 macro: ${matchedMacro.name}",
                    brainTier = BrainTier.TIER_0_MACRO
                ),
                engineSummary = "Tier 0 Macro (${matchedMacro.name})"
            )
        }

        // Tier 1: Embedded Local Engine (LiteRT-LM or llama.cpp GGUF)
        if (!forceCloud && ModelManager.isAnyLocalEngineLoaded()) {
            onStatus("Tier 1 · Running ${ModelManager.activeModelFileName}...", OrbVisualState.LOCAL_REFLEX)
            val userPrompt = "Goal: $goal\nPackage: ${snapshot.packageName}\nUI Tree:\n${snapshot.compactTree}"

            var lastError = ""
            for (attempt in 1..2) {
                val attemptPrompt = if (attempt == 1) {
                    userPrompt
                } else {
                    "$userPrompt\nIMPORTANT: Return ONLY raw valid JSON with no markdown."
                }

                val localOut = ModelManager.generateWithActiveEngine(systemPrompt, attemptPrompt)
                if (localOut.success) {
                    val parsed = parseProposedAction(localOut.text, snapshot.packageName, BrainTier.TIER_1_LOCAL)
                    if (parsed != null) {
                        if (parsed.needsCloudEscalation && geminiApiKey.isNotBlank()) {
                            onStatus("Tier 2 · Escalating to $geminiModel...", OrbVisualState.CLOUD_CONSULTANT)
                            return requestCloudConsultant(
                                apiKey = geminiApiKey,
                                modelName = geminiModel,
                                thinkingLevel = thinkingLevel,
                                enableSearchGrounding = enableSearchGrounding,
                                goal = goal,
                                snapshot = snapshot
                            )
                        }
                        return BrainResult(
                            success = true,
                            action = parsed,
                            rawResponse = localOut.text,
                            latencyMs = localOut.latencyMs,
                            engineSummary = localOut.engineLabel,
                            tokensPerSecond = localOut.tokensPerSecond
                        )
                    }
                    lastError = "Local JSON parse failed (attempt $attempt/2)"
                } else {
                    lastError = localOut.errorMessage
                }
            }

            // Escalate to Tier 2 Cloud after 2 local failures if Gemini key is configured
            if (geminiApiKey.isNotBlank()) {
                onStatus("Tier 2 · Local failed twice, escalating to $geminiModel...", OrbVisualState.CLOUD_CONSULTANT)
                return requestCloudConsultant(
                    apiKey = geminiApiKey,
                    modelName = geminiModel,
                    thinkingLevel = thinkingLevel,
                    enableSearchGrounding = enableSearchGrounding,
                    goal = goal,
                    snapshot = snapshot
                )
            }
            return BrainResult(success = false, errorMessage = lastError)
        }

        // Optional Tier 1 Fallback: Custom Termux llama-server URL (if explicitly enabled)
        if (!forceCloud && enableTermuxFallback && localServerUrl.isNotBlank()) {
            onStatus("Tier 1 · Querying Termux fallback server...", OrbVisualState.LOCAL_REFLEX)
            val termuxRes = requestTermuxFallback(localServerUrl, localModelId, goal, snapshot)
            if (termuxRes.success && termuxRes.action?.needsCloudEscalation != true) {
                return termuxRes
            }
        }

        // Tier 2: Cloud Consultant Fallback (gemini-4-hydrogen)
        onStatus("Tier 2 · Consulting $geminiModel...", OrbVisualState.CLOUD_CONSULTANT)
        return requestCloudConsultant(
            apiKey = geminiApiKey,
            modelName = geminiModel,
            thinkingLevel = thinkingLevel,
            enableSearchGrounding = enableSearchGrounding,
            goal = goal,
            snapshot = snapshot
        )
    }

    fun requestTermuxFallback(
        endpointUrl: String,
        modelId: String,
        goal: String,
        snapshot: ScreenSnapshot
    ): BrainResult {
        val started = System.currentTimeMillis()
        return try {
            val payload = JSONObject().apply {
                put("model", modelId)
                put("temperature", 0.1)
                put("top_k", 50)
                put("repeat_penalty", 1.1)
                put(
                    "messages",
                    JSONArray().apply {
                        put(JSONObject().put("role", "system").put("content", systemPrompt))
                        put(
                            JSONObject().put("role", "user").put(
                                "content",
                                "Goal: $goal\nPackage: ${snapshot.packageName}\nUI Tree:\n${snapshot.compactTree}"
                            )
                        )
                    }
                )
            }

            val response = postJson(endpointUrl, payload.toString(), 12000)
            if (response.first !in 200..299) {
                return BrainResult(
                    success = false,
                    rawResponse = response.second,
                    errorMessage = "Termux server HTTP ${response.first}",
                    latencyMs = System.currentTimeMillis() - started
                )
            }

            val root = JSONObject(response.second)
            val content = root.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            val parsed = parseProposedAction(content, snapshot.packageName, BrainTier.TIER_1_LOCAL)
            BrainResult(
                success = parsed != null,
                action = parsed,
                rawResponse = content,
                errorMessage = if (parsed == null) "Could not parse Termux ProposedAction JSON" else "",
                latencyMs = System.currentTimeMillis() - started,
                engineSummary = "Termux Local Fallback"
            )
        } catch (ex: Exception) {
            BrainResult(
                success = false,
                errorMessage = ex.message ?: "Termux server unreachable",
                latencyMs = System.currentTimeMillis() - started
            )
        }
    }

    fun requestCloudConsultant(
        apiKey: String,
        modelName: String,
        thinkingLevel: String,
        enableSearchGrounding: Boolean,
        goal: String,
        snapshot: ScreenSnapshot
    ): BrainResult {
        val started = System.currentTimeMillis()
        if (apiKey.isBlank()) {
            return BrainResult(
                success = false,
                errorMessage = "Load a local model in Brains tab or configure a Gemini API key.",
                latencyMs = 0L
            )
        }

        val safeModel = modelName.trim().ifEmpty { AegisDefaults.GEMINI_MODEL }
        val encodedKey = URLEncoder.encode(apiKey.trim(), "UTF-8")
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$safeModel:generateContent?key=$encodedKey"

        val response = executeGeminiCall(
            endpoint = endpoint,
            thinkingLevel = thinkingLevel,
            includeGrounding = enableSearchGrounding,
            goal = goal,
            snapshot = snapshot
        )

        val finalResponse = if (
            enableSearchGrounding &&
            response.first !in 200..299 &&
            (response.second.contains("google_search", ignoreCase = true) ||
                response.second.contains("grounding", ignoreCase = true) ||
                response.first == 400 ||
                response.first == 403 ||
                response.first == 429)
        ) {
            executeGeminiCall(
                endpoint = endpoint,
                thinkingLevel = thinkingLevel,
                includeGrounding = false,
                goal = goal,
                snapshot = snapshot
            )
        } else {
            response
        }

        val elapsed = System.currentTimeMillis() - started
        if (finalResponse.first !in 200..299) {
            return BrainResult(
                success = false,
                rawResponse = finalResponse.second,
                errorMessage = "Gemini HTTP ${finalResponse.first}",
                latencyMs = elapsed
            )
        }

        return try {
            val root = JSONObject(finalResponse.second)
            val text = root.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")

            val parsed = parseProposedAction(text, snapshot.packageName, BrainTier.TIER_2_CLOUD)
            BrainResult(
                success = parsed != null,
                action = parsed,
                rawResponse = text,
                errorMessage = if (parsed == null) "Could not parse Cloud ProposedAction JSON" else "",
                latencyMs = elapsed,
                engineSummary = "Cloud ($safeModel)"
            )
        } catch (ex: Exception) {
            BrainResult(
                success = false,
                rawResponse = finalResponse.second,
                errorMessage = ex.message ?: "Cloud parse error",
                latencyMs = elapsed
            )
        }
    }

    private fun executeGeminiCall(
        endpoint: String,
        thinkingLevel: String,
        includeGrounding: Boolean,
        goal: String,
        snapshot: ScreenSnapshot
    ): Pair<Int, String> {
        val generationConfig = JSONObject().apply {
            put("temperature", 0.2)
            put(
                "thinkingConfig",
                JSONObject().put("thinkingLevel", thinkingLevel.lowercase())
            )
        }

        val body = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemPrompt))
                )
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user").put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put(
                                "text",
                                "Goal: $goal\nPackage: ${snapshot.packageName}\nUI Tree:\n${snapshot.compactTree}"
                            )
                        )
                    )
                )
            )
            put("generationConfig", generationConfig)
            if (includeGrounding) {
                put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            }
        }

        return try {
            postJson(endpoint, body.toString(), 20000)
        } catch (ex: Exception) {
            Pair(0, ex.message ?: "Network error")
        }
    }

    fun pingLocalServer(endpointUrl: String): Pair<Boolean, String> {
        val started = System.currentTimeMillis()
        return try {
            val base = endpointUrl.substringBefore("/v1/")
            val healthUrl = if (base.startsWith("http")) "$base/health" else endpointUrl
            val conn = (URL(healthUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3500
                readTimeout = 3500
            }
            val code = conn.responseCode
            conn.disconnect()
            val ms = System.currentTimeMillis() - started
            if (code in 200..299) {
                Pair(true, "Custom Termux server online (HTTP $code · ${ms}ms)")
            } else {
                Pair(false, "Custom server responded HTTP $code (${ms}ms)")
            }
        } catch (ex: Exception) {
            Pair(false, "Custom server offline: ${ex.message ?: "Connection refused"}")
        }
    }

    fun testCloudKey(apiKey: String, modelName: String, thinkingLevel: String): Pair<Boolean, String> {
        if (apiKey.isBlank()) {
            return Pair(false, "Enter a Gemini API key first.")
        }
        val probeSnapshot = ScreenSnapshot(
            packageName = "com.example.probe",
            compactTree = "[id/probe] TextView \"Ping\" (100,100)",
            sha256 = "probe",
            strippedInvisibleChars = 0,
            sawUntrustedContent = false,
            capturedAtMs = System.currentTimeMillis()
        )
        val result = requestCloudConsultant(
            apiKey = apiKey,
            modelName = modelName,
            thinkingLevel = thinkingLevel,
            enableSearchGrounding = false,
            goal = "Return DONE immediately",
            snapshot = probeSnapshot
        )
        return if (result.success) {
            Pair(true, "Gemini $modelName verified (${result.latencyMs}ms)")
        } else {
            Pair(false, "Cloud test failed: ${result.errorMessage}")
        }
    }

    fun parseProposedAction(
        rawText: String,
        fallbackPackage: String,
        tier: BrainTier
    ): ProposedAction? {
        val jsonBlock = extractJsonObject(rawText) ?: return null
        return try {
            val obj = JSONObject(jsonBlock)
            val actionRaw = obj.optString("actionType", "DONE").uppercase()
            val actionType = ActionType.entries.firstOrNull { it.name == actionRaw } ?: ActionType.DONE
            ProposedAction(
                actionType = actionType,
                targetPackage = obj.optString("targetPackage", fallbackPackage).ifBlank { fallbackPackage },
                targetNodeId = obj.optString("targetNodeId", ""),
                targetLabel = obj.optString("targetLabel", ""),
                textPayload = obj.optString("textPayload", ""),
                answerText = obj.optString("answerText", ""),
                needsCloudEscalation = obj.optBoolean("needsCloudEscalation", false),
                x = obj.optDouble("x", 0.0).toFloat(),
                y = obj.optDouble("y", 0.0).toFloat(),
                endX = obj.optDouble("endX", 0.0).toFloat(),
                endY = obj.optDouble("endY", 0.0).toFloat(),
                rationale = obj.optString("rationale", ""),
                brainTier = tier
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun extractJsonObject(raw: String): String? {
        val cleaned = raw
            .replace("^```json".toRegex(RegexOption.MULTILINE), "")
            .replace("^```".toRegex(RegexOption.MULTILINE), "")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return cleaned.substring(start, end + 1)
    }

    private fun postJson(urlStr: String, body: String, timeoutMs: Int): Pair<Int, String> {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
        }

        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(body)
            writer.flush()
        }

        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val text = if (stream != null) {
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        } else {
            ""
        }
        conn.disconnect()
        return Pair(status, text)
    }
}
