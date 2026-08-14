package com.sagesearch.android.modelruntime

import java.io.File
import java.io.InputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ModelMetadata(
    val fileName: String,
    val displayName: String,
    val sizeBytes: Long,
    val sha256: String,
    val runtimeVersion: String,
)

sealed interface ModelState {
    data object NotPrepared : ModelState
    data class Importing(val copiedBytes: Long, val totalBytes: Long?) : ModelState
    data object Initializing : ModelState
    data class Ready(val metadata: ModelMetadata, val notice: String? = null) : ModelState
    data class NeedsAttention(val message: String) : ModelState
}

interface ModelRepository {
    val state: StateFlow<ModelState>
    suspend fun restore()
    suspend fun importModel(contentUri: String)

    data object NotPrepared : ModelRepository {
        private val current = MutableStateFlow<ModelState>(ModelState.NotPrepared)
        override val state: StateFlow<ModelState> = current
        override suspend fun restore() = Unit
        override suspend fun importModel(contentUri: String) = Unit
    }
}

data class ModelSource(
    val displayName: String,
    val sizeBytes: Long?,
    val openStream: () -> InputStream,
)

fun interface ModelSourceResolver {
    fun resolve(contentUri: String): ModelSource
}

interface ModelMetadataStore {
    suspend fun read(): ModelMetadata?
    suspend fun write(metadata: ModelMetadata)
    suspend fun clear()
}

data class ImportedModel(
    val file: File,
    val displayName: String,
    val sizeBytes: Long,
    val sha256: String,
)

data class GenerationPolicy(
    val temperature: Double = 0.0,
    val topK: Int = 1,
    val topP: Double = 1.0,
    val maxOutputTokens: Int = 160,
    val seed: Int = 0,
    val responseJsonSchema: String? = null,
)

interface GemmaEngine : AutoCloseable {
    suspend fun initialize()
    suspend fun generate(prompt: String, policy: GenerationPolicy): String
}

interface GemmaEngineFactory {
    fun create(modelFile: File): GemmaEngine
    fun cleanupCachesExcept(modelFileName: String?)
}

open class ModelPreparationException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

class UnsupportedModelContainer(displayName: String) :
    ModelPreparationException("$displayName is not a .litertlm model container.")

class InsufficientModelStorage(requiredBytes: Long, availableBytes: Long) :
    ModelPreparationException(
        "Not enough private storage for the model. Required $requiredBytes bytes; $availableBytes bytes are available.",
    )

class ModelSourceUnavailable(cause: Throwable) :
    ModelPreparationException("The selected model could not be read.", cause)

class IncompatibleModelRuntime(cause: Throwable) :
    ModelPreparationException("The selected model is not compatible with LiteRT-LM 0.16.0 on this device.", cause)
