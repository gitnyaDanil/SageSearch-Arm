package com.sagesearch.android.modelruntime

import com.sagesearch.android.index.HeavyWorkCoordinator
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DefaultModelRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun readyIsPublishedOnlyAfterEngineInitializationCompletes() = runTest {
        val bytes = "model".encodeToByteArray()
        val initializationGate = CompletableDeferred<Unit>()
        val engine = GatedEngine(initializationGate)
        val metadataStore = MemoryMetadataStore()
        val repository = DefaultModelRepository(
            sourceResolver = ModelSourceResolver {
                ModelSource("gemma-4-E2B-it.litertlm", bytes.size.toLong()) {
                    ByteArrayInputStream(bytes)
                }
            },
            files = ModelFileStore(temporaryFolder.newFolder("models")) { Long.MAX_VALUE },
            metadataStore = metadataStore,
            engines = ReusableGemmaEngineManager(SingleEngineFactory(engine), HeavyWorkCoordinator()),
        )

        val import = async { repository.importModel("content://model") }
        while (repository.state.value !is ModelState.Initializing) yield()
        assertFalse(repository.state.value is ModelState.Ready)

        initializationGate.complete(Unit)
        import.await()

        assertTrue(repository.state.value is ModelState.Ready)
        assertEquals(DefaultModelRepository.LITERT_LM_VERSION, metadataStore.value?.runtimeVersion)
    }

    private class GatedEngine(
        private val gate: CompletableDeferred<Unit>,
    ) : GemmaEngine {
        override suspend fun initialize() = gate.await()
        override suspend fun generate(prompt: String, policy: GenerationPolicy): String = "{}"
        override fun close() = Unit
    }

    private class SingleEngineFactory(
        private val engine: GemmaEngine,
    ) : GemmaEngineFactory {
        override fun create(modelFile: File): GemmaEngine = engine
        override fun cleanupCachesExcept(modelFileName: String?) = Unit
    }

    private class MemoryMetadataStore : ModelMetadataStore {
        var value: ModelMetadata? = null
        override suspend fun read(): ModelMetadata? = value
        override suspend fun write(metadata: ModelMetadata) {
            value = metadata
        }
        override suspend fun clear() {
            value = null
        }
    }
}
