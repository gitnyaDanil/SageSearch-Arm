package com.sagesearch.android

import com.sagesearch.android.data.repository.PrototypeImageRepository
import com.sagesearch.android.data.repository.SourceAccessRepository
import com.sagesearch.android.data.repository.SourceAccessSnapshot
import com.sagesearch.android.data.repository.SourceApprovalResult
import com.sagesearch.android.data.storage.FileOpenOutcome
import com.sagesearch.android.data.storage.OriginalFileOpener
import com.sagesearch.android.model.SearchPlan
import com.sagesearch.android.model.SearchResult
import com.sagesearch.android.modelruntime.ModelRepository
import com.sagesearch.android.search.SearchRepository
import com.sagesearch.android.ui.search.SearchViewModel
import com.sagesearch.android.ui.setup.SetupTaskStatus
import com.sagesearch.android.ui.setup.SetupViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SageSearchViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun setupKeepsSourceAndModelReadinessIndependent() = runTest {
        val viewModel = SetupViewModel(
            FakeImages(indexed = sampleImages),
            ModelRepository.NotPrepared,
            FakeSources(searchableDocuments = 1),
        )

        assertEquals(SetupTaskStatus.READY, viewModel.state.value.sourceStatus)
        assertEquals(SetupTaskStatus.NOT_STARTED, viewModel.state.value.modelStatus)
        assertEquals(1, viewModel.state.value.indexedCount)
        assertTrue(viewModel.state.value.canSearch)
    }

    @Test
    fun setupObservesIndexCheckpointProgressWithoutReopening() = runTest {
        val sources = FakeSources(searchableDocuments = 1, completedDocuments = 0, pendingDocuments = 1)
        val viewModel = SetupViewModel(
            FakeImages(indexed = sampleImages),
            ModelRepository.NotPrepared,
            sources,
        )

        assertEquals(1, viewModel.state.value.pendingIndexCount)
        sources.updateProgress(completed = 1, pending = 0)

        assertEquals(0, viewModel.state.value.pendingIndexCount)
        assertEquals(1, viewModel.state.value.completedIndexCount)
    }

    @Test
    fun searchAndNewSearchProduceDurableSimpleStates() = runTest {
        val viewModel = SearchViewModel(FakeSearchRepository(sampleResults), FakeFileOpener())
        viewModel.onQueryChanged("Fitness")
        viewModel.search()

        assertTrue(viewModel.state.value.hasSearched)
        assertEquals(1, viewModel.state.value.results.size)
        assertFalse(viewModel.state.value.isSearching)

        viewModel.newSearch()
        assertEquals("", viewModel.state.value.query)
        assertTrue(viewModel.state.value.results.isEmpty())
        assertFalse(viewModel.state.value.hasSearched)
    }

    @Test
    fun blankSearchDoesNotQueryRepository() = runTest {
        val repository = FakeSearchRepository(sampleResults)
        val viewModel = SearchViewModel(repository, FakeFileOpener())

        viewModel.search()

        assertEquals(0, repository.searchCalls)
        assertEquals("Describe the file you remember.", viewModel.state.value.message)
    }

    @Test
    fun searchPaginatesTenAtATimeFromStableRanking() = runTest {
        val results = (1L..25L).map(::searchResult)
        val viewModel = SearchViewModel(FakeSearchRepository(results), FakeFileOpener())
        viewModel.onQueryChanged("receipt")

        viewModel.search()
        assertEquals(10, viewModel.state.value.results.size)
        assertEquals(25, viewModel.state.value.totalResultCount)
        assertTrue(viewModel.state.value.canShowMore)

        viewModel.showMore()
        assertEquals(20, viewModel.state.value.results.size)
        viewModel.showMore()
        assertEquals(25, viewModel.state.value.results.size)
        assertFalse(viewModel.state.value.canShowMore)
    }

    @Test
    fun unavailableOriginalIsRemovedWithoutClearingOtherResults() = runTest {
        val repository = FakeSearchRepository((1L..3L).map(::searchResult))
        val viewModel = SearchViewModel(repository, FakeFileOpener(FileOpenOutcome.Unavailable))
        viewModel.onQueryChanged("receipt")
        viewModel.search()

        viewModel.openFile(viewModel.state.value.results.first())

        assertEquals(listOf(2L, 3L), viewModel.state.value.results.map(SearchResult::documentId))
        assertEquals(listOf(1L), repository.removedIds)
        assertEquals("The original file is no longer available.", viewModel.state.value.message)
    }

    private class FakeImages(
        private val indexed: List<IndexedImage>,
    ) : PrototypeImageRepository {
        var searchCalls = 0

        override suspend fun indexedCount(): Int = indexed.size

        override suspend fun analyzeAndIndex(contentUri: String): ImageAnalysisResult = error("Not used")

        override suspend fun search(query: String): List<IndexedImage> {
            searchCalls += 1
            return indexed.filter { it.ocrText.contains(query, ignoreCase = true) }
        }
    }

    private class FakeSources(
        private val searchableDocuments: Int,
        completedDocuments: Int = 0,
        pendingDocuments: Int = 0,
    ) : SourceAccessRepository {
        private val snapshots = MutableStateFlow(
            SourceAccessSnapshot(
                approvedSourceCount = 1,
                searchableDocumentCount = searchableDocuments,
                needsAccessCount = 0,
                completedDocumentCount = completedDocuments,
                pendingDocumentCount = pendingDocuments,
            ),
        )

        override suspend fun snapshot() = snapshots.value
        override fun observeSnapshots() = snapshots
        override suspend fun refreshAll() = snapshot()
        override suspend fun approveTree(uri: String) = SourceApprovalResult(1, searchableDocuments)
        override suspend fun approveDocuments(uris: List<String>) = SourceApprovalResult(uris.size, uris.size)

        fun updateProgress(completed: Int, pending: Int) {
            snapshots.value = snapshots.value.copy(
                completedDocumentCount = completed,
                pendingDocumentCount = pending,
            )
        }
    }

    private class FakeSearchRepository(
        private val results: List<SearchResult>,
    ) : SearchRepository {
        var searchCalls = 0
        val removedIds = mutableListOf<Long>()

        override suspend fun search(rawQuery: String): List<SearchResult> {
            searchCalls += 1
            return results
        }

        override suspend fun search(plan: SearchPlan): List<SearchResult> = results

        override suspend fun removeUnavailable(documentId: Long) {
            removedIds += documentId
        }
    }

    private class FakeFileOpener(
        private val outcome: FileOpenOutcome = FileOpenOutcome.Opened,
    ) : OriginalFileOpener {
        override fun open(result: SearchResult) = outcome
    }

    companion object {
        private val sampleResults = listOf(searchResult(1L))

        private fun searchResult(id: Long) = SearchResult(
            documentId = id,
            contentUri = "content://demo/$id",
            displayName = "receipt-$id.jpg",
            mimeType = "image/jpeg",
            evidence = emptyList(),
            stableRankKey = id.toString(),
        )

        private val sampleImages = listOf(
            IndexedImage(
                imageUri = "content://demo/receipt",
                analyzedAtMillis = 1L,
                contentKind = "receipt",
                receiptConfidence = 0.9,
                ocrText = "Fitness Center Membership",
                merchantCandidate = "Fitness Center",
                transactionDateText = "March 12",
                totalText = "IDR 200,000",
                total = 200_000.0,
                currency = "IDR",
            ),
        )
    }
}
