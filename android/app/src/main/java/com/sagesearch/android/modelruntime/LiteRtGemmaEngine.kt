package com.sagesearch.android.modelruntime

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ResponseFormat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LiteRtGemmaEngineFactory(
    private val cacheRoot: File,
) : GemmaEngineFactory {
    override fun create(modelFile: File): GemmaEngine {
        val modelCache = File(cacheRoot, modelFile.nameWithoutExtension).apply { mkdirs() }
        return LiteRtGemmaEngine(modelFile, modelCache)
    }

    override fun cleanupCachesExcept(modelFileName: String?) {
        if (!cacheRoot.exists()) return
        val keepDirectory = modelFileName?.let { File(it).nameWithoutExtension }
        cacheRoot.listFiles()
            .orEmpty()
            .filter { it.name != keepDirectory }
            .forEach(File::deleteRecursively)
    }
}

private class LiteRtGemmaEngine(
    modelFile: File,
    cacheDirectory: File,
) : GemmaEngine {
    private val engine = Engine(
        EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = Backend.CPU(),
            cacheDir = cacheDirectory.absolutePath,
        ),
    )

    override suspend fun initialize() = withContext(Dispatchers.IO) {
        engine.initialize()
    }

    override suspend fun generate(prompt: String, policy: GenerationPolicy): String =
        withContext(Dispatchers.IO) {
            val config = ConversationConfig(
                systemInstruction = Contents.of(
                    "Return only the compact search-plan data requested. Never invent file contents.",
                ),
                samplerConfig = SamplerConfig(
                    topK = policy.topK,
                    topP = policy.topP,
                    temperature = policy.temperature,
                    seed = policy.seed,
                ),
                maxOutputToken = policy.maxOutputTokens,
                enableResponseFormat = policy.responseJsonSchema != null,
            )
            engine.createConversation(config).use { conversation ->
                val response = conversation.sendMessage(
                    text = prompt,
                    responseFormat = policy.responseJsonSchema?.let(ResponseFormat::json),
                )
                response.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString(separator = "") { it.text }
                    .trim()
                    .take(MAX_OUTPUT_CHARACTERS)
            }
        }

    override fun close() {
        engine.close()
    }

    companion object {
        private const val MAX_OUTPUT_CHARACTERS = 8_192
    }
}
