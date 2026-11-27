package com.siki2solo.aegisagent

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Engine A: Embedded Google LiteRT-LM (litertlm-android:0.18.1) for .litertlm weights.
 * All initialize(), createConversation(), sendMessage(), and close() calls run strictly
 * on a dedicated single-thread executor and never on the main thread.
 */
class LiteRtBrainEngine {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "aegis-litertlm-worker").apply { isDaemon = true }
    }

    private var activeEngine: Engine? = null
    private val closedGuard = AtomicBoolean(true)

    @Volatile
    var loadedModelPath: String = ""
        private set

    @Volatile
    var activeBackendLabel: String = "Unloaded"
        private set

    fun isLoaded(): Boolean = activeEngine != null && !closedGuard.get()

    fun loadModel(
        context: Context,
        modelFile: File,
        preferGpu: Boolean
    ): Pair<Boolean, String> {
        return executor.submit(Callable {
            unloadInternal()
            if (!modelFile.exists() || !modelFile.canRead()) {
                return@Callable Pair(false, "Model file not found: ${modelFile.name}")
            }

            Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
            val cacheDir = context.cacheDir.absolutePath
            val path = modelFile.absolutePath

            if (preferGpu) {
                try {
                    val gpuConfig = EngineConfig(
                        modelPath = path,
                        backend = Backend.GPU(),
                        cacheDir = cacheDir
                    )
                    val gpuEngine = Engine(gpuConfig)
                    gpuEngine.initialize()
                    activeEngine = gpuEngine
                    closedGuard.set(false)
                    loadedModelPath = path
                    activeBackendLabel = "LiteRT-LM (GPU)"
                    return@Callable Pair(true, "Loaded ${modelFile.name} on LiteRT-LM GPU")
                } catch (_: Throwable) {
                    // Automatic fallback to CPU backend when GPU delegate is unsupported
                }
            }

            try {
                val cpuConfig = EngineConfig(
                    modelPath = path,
                    backend = Backend.CPU(),
                    cacheDir = cacheDir
                )
                val cpuEngine = Engine(cpuConfig)
                cpuEngine.initialize()
                activeEngine = cpuEngine
                closedGuard.set(false)
                loadedModelPath = path
                activeBackendLabel = "LiteRT-LM (CPU)"
                Pair(true, "Loaded ${modelFile.name} on LiteRT-LM CPU")
            } catch (ex: Throwable) {
                activeEngine = null
                closedGuard.set(true)
                loadedModelPath = ""
                activeBackendLabel = "Unloaded"
                Pair(false, "LiteRT-LM init failed: ${ex.message ?: ex.javaClass.simpleName}")
            }
        }).get()
    }

    fun generate(
        systemPrompt: String,
        userPrompt: String
    ): LocalInferenceOutput {
        return executor.submit(Callable {
            val engine = activeEngine
            if (engine == null || closedGuard.get()) {
                return@Callable LocalInferenceOutput(
                    success = false,
                    engineLabel = "LiteRT-LM",
                    errorMessage = "No .litertlm model loaded."
                )
            }

            val started = System.currentTimeMillis()
            try {
                val conversationConfig = ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt),
                    samplerConfig = SamplerConfig(
                        topK = 50,
                        topP = 0.95,
                        temperature = 0.1
                    )
                )

                val text = engine.createConversation(conversationConfig).use { conversation ->
                    val response = conversation.sendMessage(userPrompt)
                    response.toString().trim()
                }

                val elapsedMs = (System.currentTimeMillis() - started).coerceAtLeast(1L)
                val approxTokens = (text.split(Regex("\\s+")).count { it.isNotBlank() } * 1.35).coerceAtLeast(1.0)
                val tps = approxTokens / (elapsedMs / 1000.0)

                LocalInferenceOutput(
                    success = text.isNotBlank(),
                    text = text,
                    tokensPerSecond = tps,
                    latencyMs = elapsedMs,
                    engineLabel = activeBackendLabel,
                    errorMessage = if (text.isBlank()) "Empty LiteRT-LM output" else ""
                )
            } catch (ex: Throwable) {
                LocalInferenceOutput(
                    success = false,
                    latencyMs = System.currentTimeMillis() - started,
                    engineLabel = activeBackendLabel,
                    errorMessage = ex.message ?: "LiteRT-LM inference failed"
                )
            }
        }).get()
    }

    fun unload() {
        try {
            executor.submit { unloadInternal() }.get()
        } catch (_: Exception) {
        }
    }

    private fun unloadInternal() {
        if (closedGuard.compareAndSet(false, true)) {
            try {
                activeEngine?.close()
            } catch (_: Throwable) {
            }
        }
        activeEngine = null
        loadedModelPath = ""
        activeBackendLabel = "Unloaded"
    }
}
