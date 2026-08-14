package com.sagesearch.android.modelruntime

import com.sagesearch.android.index.HeavyWorkCoordinator
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReusableGemmaEngineManagerTest {
    @Test
    fun compatibleModelBecomesReusableAndUsesDeterministicPolicy() = runTest {
        val engine = FakeEngine("plan")
        val manager = managerWith(engine)

        manager.activate(File("gemma.litertlm"))
        val output = manager.generateOneShot("find my receipt")

        assertEquals("plan", output)
        assertTrue(engine.initialized)
        assertEquals(GenerationPolicy(), engine.policies.single())
    }

    @Test
    fun incompatibleCandidateIsClosedAndPreviousEngineRemainsActive() = runTest {
        val previous = FakeEngine("old")
        val incompatible = FakeEngine("new", failInitialization = true)
        val factory = QueueFactory(previous, incompatible)
        val manager = ReusableGemmaEngineManager(factory, HeavyWorkCoordinator())
        manager.activate(File("old.litertlm"))

        val error = runCatching { manager.activate(File("bad.litertlm")) }.exceptionOrNull()

        assertTrue(error is IncompatibleModelRuntime)
        assertTrue(incompatible.closed)
        assertFalse(previous.closed)
        assertEquals("old", manager.generateOneShot("query"))
    }

    @Test
    fun successfulReplacementClosesOldEngineAndKeepsNewEngine() = runTest {
        val old = FakeEngine("old")
        val replacement = FakeEngine("new")
        val factory = QueueFactory(old, replacement)
        val manager = ReusableGemmaEngineManager(factory, HeavyWorkCoordinator())
        manager.activate(File("old.litertlm"))

        manager.activate(File("new.litertlm"))

        assertTrue(old.closed)
        assertFalse(replacement.closed)
        assertEquals("new", manager.generateOneShot("query"))
        assertEquals(listOf("new.litertlm"), factory.cacheKeeps.takeLast(1))
    }

    @Test
    fun concurrentRequestsRunOneInferenceAtATime() = runTest {
        val engine = FakeEngine("plan", generationDelayMillis = 20L)
        val manager = managerWith(engine)
        manager.activate(File("gemma.litertlm"))

        val first = async { manager.generateOneShot("one") }
        val second = async { manager.generateOneShot("two") }
        val third = async { manager.generateOneShot("three") }
        listOf(first, second, third).forEach { it.await() }

        assertEquals(1, engine.maximumConcurrentGeneration.get())
        assertEquals(3, engine.policies.size)
    }

    private fun managerWith(engine: FakeEngine): ReusableGemmaEngineManager =
        ReusableGemmaEngineManager(QueueFactory(engine), HeavyWorkCoordinator())

    private class QueueFactory(vararg engines: FakeEngine) : GemmaEngineFactory {
        private val queue = ArrayDeque(engines.toList())
        val cacheKeeps = mutableListOf<String?>()

        override fun create(modelFile: File): GemmaEngine = queue.removeFirst()

        override fun cleanupCachesExcept(modelFileName: String?) {
            cacheKeeps += modelFileName
        }
    }

    private class FakeEngine(
        private val output: String,
        private val failInitialization: Boolean = false,
        private val generationDelayMillis: Long = 0L,
    ) : GemmaEngine {
        var initialized = false
        var closed = false
        val policies = mutableListOf<GenerationPolicy>()
        private val concurrentGeneration = AtomicInteger()
        val maximumConcurrentGeneration = AtomicInteger()

        override suspend fun initialize() {
            if (failInitialization) error("incompatible")
            initialized = true
        }

        override suspend fun generate(prompt: String, policy: GenerationPolicy): String {
            policies += policy
            val active = concurrentGeneration.incrementAndGet()
            maximumConcurrentGeneration.updateAndGet { maxOf(it, active) }
            try {
                delay(generationDelayMillis)
                return output
            } finally {
                concurrentGeneration.decrementAndGet()
            }
        }

        override fun close() {
            closed = true
        }
    }
}
