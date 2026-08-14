package com.sagesearch.android

import com.sagesearch.android.index.HeavyWorkCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HeavyWorkCoordinatorTest {
    @Test
    fun interactiveWorkTakesTheNextSlotAfterCurrentBackgroundDocument() = runTest {
        val coordinator = HeavyWorkCoordinator()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        launch {
            coordinator.withBackgroundWork {
                order += "background-1"
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
        }
        firstStarted.await()
        launch { coordinator.withBackgroundWork { order += "background-2" } }
        launch { coordinator.withInteractiveWork { order += "interactive" } }
        runCurrent()

        releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("background-1", "interactive", "background-2"), order)
    }
}
