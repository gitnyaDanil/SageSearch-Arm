package com.sagesearch.android.modelruntime

import com.sagesearch.android.index.HeavyWorkCoordinator
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ReusableGemmaEngineManager(
    private val factory: GemmaEngineFactory,
    private val heavyWork: HeavyWorkCoordinator,
    private val policy: GenerationPolicy = GenerationPolicy(),
) {
    private val engineMutex = Mutex()
    private var active: ActiveEngine? = null

    suspend fun activate(modelFile: File) {
        heavyWork.withInteractiveWork {
            engineMutex.withLock {
                val candidate = try {
                    factory.create(modelFile)
                } catch (error: Throwable) {
                    throw IncompatibleModelRuntime(error)
                }
                try {
                    candidate.initialize()
                } catch (cancelled: CancellationException) {
                    runCatching(candidate::close)
                    throw cancelled
                } catch (error: Throwable) {
                    runCatching(candidate::close)
                    throw IncompatibleModelRuntime(error)
                }

                val previous = active
                active = ActiveEngine(modelFile.name, candidate)
                runCatching { previous?.engine?.close() }
                withContext(Dispatchers.IO) {
                    factory.cleanupCachesExcept(modelFile.name)
                }
            }
        }
    }

    suspend fun generateOneShot(
        prompt: String,
        generationPolicy: GenerationPolicy = policy,
    ): String =
        heavyWork.withInteractiveWork {
            engineMutex.withLock {
                val current = active ?: error("The on-device model is not ready.")
                current.engine.generate(prompt, generationPolicy)
            }
        }

    suspend fun shutdown(clearPrivateCache: Boolean = false) {
        engineMutex.withLock {
            active?.engine?.close()
            active = null
            if (clearPrivateCache) {
                withContext(Dispatchers.IO) { factory.cleanupCachesExcept(null) }
            }
        }
    }

    private data class ActiveEngine(
        val modelFileName: String,
        val engine: GemmaEngine,
    )
}
