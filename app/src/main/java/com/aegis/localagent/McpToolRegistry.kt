package com.aegis.localagent

import org.json.JSONArray
import org.json.JSONObject

data class McpChainStep(
    val serverUri: String,
    val toolName: String,
    val outputJson: String
)

class McpToolRegistry(
    private val ragStore: LocalRagStore,
    private val sandbox: LocalSandboxEngine
) {

    fun listCapabilities(): List<String> = listOf(
        "mcp://local-rag/query",
        "mcp://sandbox/exec",
        "mcp://device-a11y/dispatch"
    )

    fun invokeTool(serverUri: String, toolName: String, payload: String): McpChainStep {
        return when (serverUri) {
            "mcp://local-rag" -> {
                val hits = ragStore.searchFts5(payload, limit = 4)
                val arr = JSONArray()
                hits.forEach { h ->
                    arr.put(
                        JSONObject().apply {
                            put("title", h.title)
                            put("type", h.sourceType)
                            put("snippet", h.snippet)
                        }
                    )
                }
                McpChainStep(serverUri, toolName, arr.toString())
            }
            "mcp://sandbox" -> {
                val res = sandbox.executeScript(payload)
                val obj = JSONObject().apply {
                    put("success", res.success)
                    put("stdout", res.stdout)
                    put("traceback", res.traceback ?: "")
                }
                McpChainStep(serverUri, toolName, obj.toString())
            }
            else -> {
                McpChainStep(serverUri, toolName, "{\"status\":\"unsupported_uri\"}")
            }
        }
    }
}
