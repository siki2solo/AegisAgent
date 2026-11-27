package com.siki2solo.aegisagent

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

/**
 * Unified Dual-Engine In-App Model Manager.
 * Manages .litertlm and .gguf files inside context.getExternalFilesDir("models"),
 * auto-detects engine by extension, and guarantees mutual exclusion in RAM.
 */
object ModelManager {

    private const val PREFS_NAME = "aegis_agent_prefs"
    private const val KEY_LITERT_GPU_AUTO = "litert_gpu_auto"
    private const val KEY_LAST_MODEL_FILE = "last_loaded_model_file"

    val liteRtEngine = LiteRtBrainEngine()
    val llamaGgufEngine = LlamaGgufBrainEngine()

    val CURATED_MODELS: List<CuratedModelSpec> = listOf(
        CuratedModelSpec(
            id = "qwen3-0.6b-litertlm",
            fileName = "Qwen3-0.6B.litertlm",
            displayName = "Qwen3-0.6B",
            engineKind = LocalEngineKind.LITERT_LM,
            badge = "LiteRT-LM · Fast Reflex · ~614 MB · 2 GB RAM",
            downloadUrl = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm"
        ),
        CuratedModelSpec(
            id = "lfm3-1.7b-litertlm",
            fileName = "lfm3-1.7b-int4.litertlm",
            displayName = "LFM3-1.7B INT4 (LiquidAI)",
            engineKind = LocalEngineKind.LITERT_LM,
            badge = "LiteRT-LM · Agent Specialist · ~848 MB · 2–4 GB RAM",
            downloadUrl = "https://huggingface.co/cheesefelys/LFM3-1.7B-litertlm/resolve/main/lfm3-1.7b-int4.litertlm"
        ),
        CuratedModelSpec(
            id = "gemma4-e2b-litertlm",
            fileName = "gemma-4-E2B-it.litertlm",
            displayName = "Gemma 4 E2B Instruct",
            engineKind = LocalEngineKind.LITERT_LM,
            badge = "LiteRT-LM · Smart Multimodal · ~2.58 GB · 4–6 GB RAM",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
        ),
        CuratedModelSpec(
            id = "qwen2.5-1.5b-gguf",
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            displayName = "Qwen2.5-1.5B Instruct (Q4_K_M)",
            engineKind = LocalEngineKind.LLAMA_GGUF,
            badge = "GGUF · llama.cpp CPU + Embeddings · ~986 MB · 2–4 GB RAM",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            note = "Embedded llama.cpp AAR (b9878) runs standard GGUF models (Qwen2.5, Llama 3.2, Gemma 2, Phi-3.5) + local embeddings on CPU. LFM3-1.7B runs natively via the LiteRT-LM card above (or via the collapsed optional Custom Local Server URL fallback for Termux llama-server b11960+)."
        )
    )

    @Volatile
    var activeEngineKind: LocalEngineKind = LocalEngineKind.NONE
        private set

    @Volatile
    var activeModelFileName: String = ""
        private set

    @Volatile
    var lastTokensPerSecond: Double = 0.0
        private set

    fun modelsDir(context: Context): File {
        val dir = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun listLocalModelFiles(context: Context): List<File> {
        val dir = modelsDir(context)
        return dir.listFiles()
            ?.filter { it.isFile && detectEngineKind(it.name) != LocalEngineKind.NONE }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    fun detectEngineKind(fileName: String): LocalEngineKind {
        val lower = fileName.trim().lowercase()
        return when {
            lower.endsWith(".litertlm") -> LocalEngineKind.LITERT_LM
            lower.endsWith(".gguf") -> LocalEngineKind.LLAMA_GGUF
            else -> LocalEngineKind.NONE
        }
    }

    fun isGpuAutoEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_LITERT_GPU_AUTO, AegisDefaults.LITERT_GPU_AUTO)
    }

    fun setGpuAutoEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_LITERT_GPU_AUTO, enabled)
            .apply()
    }

    fun isAnyLocalEngineLoaded(): Boolean {
        return liteRtEngine.isLoaded() || llamaGgufEngine.isLoaded()
    }

    fun activeStatusSummary(): String {
        return when (activeEngineKind) {
            LocalEngineKind.LITERT_LM -> {
                val tps = if (lastTokensPerSecond > 0.0) " · %.1f tok/s".format(lastTokensPerSecond) else ""
                "${liteRtEngine.activeBackendLabel} · $activeModelFileName$tps"
            }
            LocalEngineKind.LLAMA_GGUF -> {
                val tps = if (lastTokensPerSecond > 0.0) " · %.1f tok/s".format(lastTokensPerSecond) else ""
                "${llamaGgufEngine.activeBackendLabel} · $activeModelFileName$tps"
            }
            LocalEngineKind.NONE -> "No embedded local model loaded"
        }
    }

    /**
     * Auto-detects engine from file extension and strictly unloads the opposite engine
     * before loading so two native model weights never coexist in device RAM.
     */
    @Synchronized
    fun loadModelFile(context: Context, file: File): Pair<Boolean, String> {
        val targetKind = detectEngineKind(file.name)
        if (targetKind == LocalEngineKind.NONE) {
            return Pair(false, "Unsupported extension. Select a .litertlm or .gguf file.")
        }

        // Guarantee mutual exclusion before allocating native memory
        unloadAll()

        val result = when (targetKind) {
            LocalEngineKind.LITERT_LM -> {
                liteRtEngine.loadModel(context, file, isGpuAutoEnabled(context))
            }
            LocalEngineKind.LLAMA_GGUF -> {
                llamaGgufEngine.loadModel(file)
            }
            LocalEngineKind.NONE -> Pair(false, "Unsupported model")
        }

        if (result.first) {
            activeEngineKind = targetKind
            activeModelFileName = file.name
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_MODEL_FILE, file.name)
                .apply()
        } else {
            activeEngineKind = LocalEngineKind.NONE
            activeModelFileName = ""
        }
        return result
    }

    @Synchronized
    fun unloadAll() {
        liteRtEngine.unload()
        llamaGgufEngine.unload()
        activeEngineKind = LocalEngineKind.NONE
        activeModelFileName = ""
        lastTokensPerSecond = 0.0
    }

    fun generateWithActiveEngine(systemPrompt: String, userPrompt: String): LocalInferenceOutput {
        val output = when (activeEngineKind) {
            LocalEngineKind.LITERT_LM -> liteRtEngine.generate(systemPrompt, userPrompt)
            LocalEngineKind.LLAMA_GGUF -> llamaGgufEngine.generate(systemPrompt, userPrompt)
            LocalEngineKind.NONE -> LocalInferenceOutput(
                success = false,
                engineLabel = "None",
                errorMessage = "No embedded local model loaded."
            )
        }
        if (output.success && output.tokensPerSecond > 0.0) {
            lastTokensPerSecond = output.tokensPerSecond
        }
        return output
    }

    fun embedTextIfAvailable(text: String): FloatArray? {
        if (activeEngineKind != LocalEngineKind.LLAMA_GGUF || !llamaGgufEngine.isLoaded()) {
            return null
        }
        return llamaGgufEngine.embedText(text)
    }

    fun testActiveEngine(): Pair<Boolean, String> {
        if (!isAnyLocalEngineLoaded()) {
            return Pair(false, "Load a .litertlm or .gguf model first.")
        }
        val out = generateWithActiveEngine(
            systemPrompt = "Reply concisely in one short sentence.",
            userPrompt = "Confirm AegisAgent local engine readiness."
        )
        return if (out.success) {
            val tpsStr = "%.1f tok/s".format(out.tokensPerSecond)
            Pair(true, "${out.engineLabel} OK ($tpsStr · ${out.latencyMs}ms): ${out.text.take(90)}")
        } else {
            Pair(false, "${out.engineLabel} error: ${out.errorMessage}")
        }
    }

    fun enqueueCuratedDownload(context: Context, spec: CuratedModelSpec): Long {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val destFile = File(modelsDir(context), spec.fileName)
        if (destFile.exists()) {
            destFile.delete()
        }
        val request = DownloadManager.Request(Uri.parse(spec.downloadUrl))
            .setTitle("Downloading ${spec.displayName}")
            .setDescription(spec.badge)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(context, "models", spec.fileName)
        return dm.enqueue(request)
    }

    fun queryDownloadStatus(context: Context, downloadId: Long): DownloadProgressInfo {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        var cursor: Cursor? = null
        return try {
            cursor = dm.query(query)
            if (cursor != null && cursor.moveToFirst()) {
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val pct = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0
                DownloadProgressInfo(
                    status = status,
                    percent = pct,
                    downloadedMb = downloaded / (1024.0 * 1024.0),
                    totalMb = if (total > 0L) total / (1024.0 * 1024.0) else 0.0
                )
            } else {
                DownloadProgressInfo(DownloadManager.STATUS_FAILED, 0, 0.0, 0.0)
            }
        } catch (_: Exception) {
            DownloadProgressInfo(DownloadManager.STATUS_FAILED, 0, 0.0, 0.0)
        } finally {
            cursor?.close()
        }
    }

    fun importDocumentUri(context: Context, uri: Uri): Pair<Boolean, String> {
        val displayName = resolveDisplayName(context, uri)
        if (detectEngineKind(displayName) == LocalEngineKind.NONE) {
            return Pair(false, "Selected file '$displayName' must end in .litertlm or .gguf")
        }
        val destFile = File(modelsDir(context), displayName)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            } ?: return Pair(false, "Could not open selected document stream.")
            Pair(true, "Imported $displayName (${formatSizeMb(destFile.length())})")
        } catch (ex: Exception) {
            Pair(false, "Import failed: ${ex.message ?: "I/O error"}")
        }
    }

    fun formatSizeMb(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) {
            "%.2f GB".format(mb / 1024.0)
        } else {
            "%.0f MB".format(mb)
        }
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String {
        var name = "imported_model.litertlm"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val candidate = cursor.getString(idx)
                    if (!candidate.isNullOrBlank()) name = candidate
                }
            }
        }
        return name
    }
}
