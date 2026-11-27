package com.siki2solo.aegisagent

import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Engine B: Embedded zero-NDK llama.cpp AAR (dev.ffmpegkit-maintained:llama-android:0.1.1)
 * for raw .gguf models and on-device semantic embeddings.
 * Runs sequentially on Dispatchers.IO.limitedParallelism(1).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LlamaGgufBrainEngine {

    private interface ActiveGgufHandle {
        suspend fun complete(prompt: String, systemPrompt: String, maxTokens: Int): Pair<String, Double>
        suspend fun embed(text: String): FloatArray?
        suspend fun release()
    }

    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var activeHandle: ActiveGgufHandle? = null
    private val releasedGuard = AtomicBoolean(true)

    @Volatile
    var loadedModelPath: String = ""
        private set

    @Volatile
    var activeBackendLabel: String = "Unloaded"
        private set

    fun isLoaded(): Boolean = activeHandle != null && !releasedGuard.get()

    fun loadModel(modelFile: File): Pair<Boolean, String> {
        return runBlocking(llamaDispatcher) {
            unloadInternal()
            if (!modelFile.exists() || !modelFile.canRead()) {
                return@runBlocking Pair(false, "GGUF file not found: ${modelFile.name}")
            }

            try {
                val model = Llama.loadModel(
                    modelPath = modelFile.absolutePath,
                    config = LlamaConfig(contextSize = 2048, threads = 4)
                )

                activeHandle = object : ActiveGgufHandle {
                    override suspend fun complete(
                        prompt: String,
                        systemPrompt: String,
                        maxTokens: Int
                    ): Pair<String, Double> {
                        val result = Llama.complete(
                            model = model,
                            prompt = prompt,
                            systemPrompt = systemPrompt,
                            maxTokens = maxTokens
                        )
                        return Pair(result.text.trim(), result.tokensPerSecond.toDouble())
                    }

                    override suspend fun embed(text: String): FloatArray? {
                        val raw: Any? = Llama.embed(model, text)
                        return when (raw) {
                            is FloatArray -> raw
                            is List<*> -> raw.mapNotNull { (it as? Number)?.toFloat() }.toFloatArray()
                            else -> null
                        }
                    }

                    override suspend fun release() {
                        Llama.releaseModel(model)
                    }
                }
                releasedGuard.set(false)
                loadedModelPath = modelFile.absolutePath
                activeBackendLabel = "llama.cpp GGUF (CPU + Embeddings)"
                Pair(true, "Loaded ${modelFile.name} via llama.cpp GGUF")
            } catch (ex: Throwable) {
                activeHandle = null
                releasedGuard.set(true)
                loadedModelPath = ""
                activeBackendLabel = "Unloaded"
                Pair(false, "llama.cpp load failed: ${ex.message ?: ex.javaClass.simpleName}")
            }
        }
    }

    fun generate(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = 384
    ): LocalInferenceOutput {
        return runBlocking(llamaDispatcher) {
            val handle = activeHandle
            if (handle == null || releasedGuard.get()) {
                return@runBlocking LocalInferenceOutput(
                    success = false,
                    engineLabel = "llama.cpp GGUF",
                    errorMessage = "No .gguf model loaded."
                )
            }

            val started = System.currentTimeMillis()
            try {
                val (text, tps) = handle.complete(
                    prompt = userPrompt,
                    systemPrompt = systemPrompt,
                    maxTokens = maxTokens
                )
                val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(1L)
                LocalInferenceOutput(
                    success = text.isNotBlank(),
                    text = text,
                    tokensPerSecond = tps,
                    latencyMs = elapsed,
                    engineLabel = activeBackendLabel,
                    errorMessage = if (text.isBlank()) "Empty GGUF output" else ""
                )
            } catch (ex: Throwable) {
                LocalInferenceOutput(
                    success = false,
                    latencyMs = System.currentTimeMillis() - started,
                    engineLabel = activeBackendLabel,
                    errorMessage = ex.message ?: "llama.cpp completion failed"
                )
            }
        }
    }

    fun embedText(text: String): FloatArray? {
        return runBlocking(llamaDispatcher) {
            val handle = activeHandle
            if (handle == null || releasedGuard.get()) return@runBlocking null
            try {
                handle.embed(text)
            } catch (_: Throwable) {
                null
            }
        }
    }

    fun unload() {
        runBlocking(llamaDispatcher) {
            unloadInternal()
        }
    }

    private suspend fun unloadInternal() {
        if (releasedGuard.compareAndSet(false, true)) {
            try {
                activeHandle?.release()
            } catch (_: Throwable) {
            }
        }
        activeHandle = null
        loadedModelPath = ""
        activeBackendLabel = "Unloaded"
    }
}
