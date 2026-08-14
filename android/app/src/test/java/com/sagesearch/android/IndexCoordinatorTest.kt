package com.sagesearch.android

import androidx.work.ExistingWorkPolicy
import com.sagesearch.android.index.IndexCoordinator
import com.sagesearch.android.index.IndexProgress
import com.sagesearch.android.index.IndexWorkBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Test

class IndexCoordinatorTest {
    @Test
    fun duplicateRefreshesUseOneStableKeepProtectedSourceName() {
        val backend = FakeIndexWorkBackend()
        val coordinator = IndexCoordinator(backend)

        coordinator.enqueueRefresh(42)
        coordinator.enqueueRefresh(42)

        assertEquals(1, backend.activeNames.size)
        assertEquals("sagesearch-index-source-42", backend.activeNames.single())
        assertEquals(listOf(ExistingWorkPolicy.KEEP, ExistingWorkPolicy.KEEP), backend.requestedPolicies)
    }

    @Test
    fun boundedContinuationAppendsToTheSameSourceChain() {
        val backend = FakeIndexWorkBackend()
        val coordinator = IndexCoordinator(backend)

        coordinator.enqueueRefresh(42)
        coordinator.enqueueContinuation(42)

        assertEquals(listOf("sagesearch-index-source-42", "sagesearch-index-source-42"), backend.requestedNames)
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, backend.requestedPolicies.last())
    }
}

private class FakeIndexWorkBackend : IndexWorkBackend {
    val activeNames = mutableSetOf<String>()
    val requestedNames = mutableListOf<String>()
    val requestedPolicies = mutableListOf<ExistingWorkPolicy>()

    override fun enqueue(uniqueName: String, policy: ExistingWorkPolicy, sourceId: Long) {
        requestedNames += uniqueName
        requestedPolicies += policy
        if (policy != ExistingWorkPolicy.KEEP || uniqueName !in activeNames) activeNames += uniqueName
    }

    override fun observeProgress(uniqueName: String): Flow<IndexProgress> = flowOf(IndexProgress(0, 0))
}
