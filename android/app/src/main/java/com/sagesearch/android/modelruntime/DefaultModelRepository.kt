package com.sagesearch.android.modelruntime

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DefaultModelRepository(
    private val sourceResolver: ModelSourceResolver,
    private val files: ModelFileStore,
    private val metadataStore: ModelMetadataStore,
    private val engines: ReusableGemmaEngineManager,
) : ModelRepository {
    private val operationMutex = Mutex()
    private val mutableState = MutableStateFlow<ModelState>(ModelState.Initializing)
    override val state: StateFlow<ModelState> = mutableState.asStateFlow()

    override suspend fun restore() {
        operationMutex.withLock {
            files.cleanupStalePartials()
            val metadata = try {
                metadataStore.read()
            } catch (_: Throwable) {
                mutableState.value = ModelState.NeedsAttention("The saved model status could not be read. Choose the model again.")
                return
            } ?: run {
                mutableState.value = ModelState.NotPrepared
                return
            }
            val modelFile = files.resolve(metadata.fileName)
            if (modelFile == null || modelFile.length() != metadata.sizeBytes) {
                metadataStore.clear()
                mutableState.value = ModelState.NeedsAttention("The private model copy is missing. Choose the model again.")
                return
            }
            mutableState.value = ModelState.Initializing
            try {
                engines.activate(modelFile)
                mutableState.value = ModelState.Ready(metadata)
            } catch (_: Throwable) {
                mutableState.value = ModelState.NeedsAttention(
                    "The saved model could not initialize on this device. Basic search still works.",
                )
            }
        }
    }

    override suspend fun importModel(contentUri: String) {
        operationMutex.withLock {
            val previous = mutableState.value as? ModelState.Ready
            var imported: ImportedModel? = null
            var activated = false
            try {
                val source = sourceResolver.resolve(contentUri)
                mutableState.value = ModelState.Importing(0L, source.sizeBytes)
                imported = files.import(source) { copied, total ->
                    mutableState.value = ModelState.Importing(copied, total)
                }
                mutableState.value = ModelState.Initializing
                engines.activate(imported.file)
                activated = true
                val metadata = ModelMetadata(
                    fileName = imported.file.name,
                    displayName = imported.displayName,
                    sizeBytes = imported.sizeBytes,
                    sha256 = imported.sha256,
                    runtimeVersion = LITERT_LM_VERSION,
                )
                metadataStore.write(metadata)
                files.cleanupModelsExcept(metadata.fileName)
                mutableState.value = ModelState.Ready(metadata)
            } catch (cancelled: CancellationException) {
                imported?.let { files.deleteCandidate(it.file, previous?.metadata?.fileName) }
                mutableState.value = previous ?: ModelState.NotPrepared
                throw cancelled
            } catch (error: Throwable) {
                if (!activated) {
                    imported?.let { files.deleteCandidate(it.file, previous?.metadata?.fileName) }
                    mutableState.value = previous?.copy(notice = userMessage(error))
                        ?: ModelState.NeedsAttention(userMessage(error))
                } else {
                    mutableState.value = ModelState.NeedsAttention(
                        "The model initialized, but its status could not be saved. Choose it again before the next launch.",
                    )
                }
            }
        }
    }

    private fun userMessage(error: Throwable): String = when (error) {
        is ModelPreparationException -> error.message ?: "The model could not be prepared."
        else -> "The selected model could not be prepared. Basic search still works."
    }

    companion object {
        const val LITERT_LM_VERSION = "0.16.0"
    }
}
