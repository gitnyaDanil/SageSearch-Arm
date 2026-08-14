package com.sagesearch.android

import com.sagesearch.android.data.storage.FileOpenOutcome
import com.sagesearch.android.data.storage.OriginalFileOpener
import com.sagesearch.android.model.EvidenceStrength
import com.sagesearch.android.model.MatchEvidence
import com.sagesearch.android.model.SearchPhase
import com.sagesearch.android.model.SearchPlan
import com.sagesearch.android.model.SearchResult
import com.sagesearch.android.model.SearchTurn
import com.sagesearch.android.planner.PlannerRejection
import com.sagesearch.android.planner.QueryInterpretation
import com.sagesearch.android.planner.QueryInterpreter
import com.sagesearch.android.planner.SafeRefinedSearchExecutor
import com.sagesearch.android.planner.LongBounds
import com.sagesearch.android.planner.StringBounds
import com.sagesearch.android.planner.ValidatedPlannerPlan
import com.sagesearch.android.search.SearchRepository
import com.sagesearch.android.search.session.SearchSessionCodec
import com.sagesearch.android.search.session.SearchSessionStore
import com.sagesearch.android.search.session.StoredSearchSession
import com.sagesearch.android.ui.search.SearchViewModel
import java.util.ArrayDeque
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchExperienceViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun initialSearchShowsPreliminaryThenAppliesAcceptedRefinement() = runTest {
        val repository = FakeSearchRepository(rawResults = listOf(result(1)), planResults = listOf(result(2)))
        val interpreter = QueueInterpreter(accepted("gym"))
        val store = RecordingStore()
        val viewModel = viewModel(repository, interpreter, store)

        viewModel.onQueryChanged("gym receipt")
        viewModel.search()
        advanceUntilIdle()

        assertEquals(SearchPhase.REFINED, viewModel.state.value.phase)
        assertEquals(listOf(2L, 1L), viewModel.state.value.results.map(SearchResult::documentId))
        assertEquals(listOf("gym receipt"), viewModel.state.value.turns.map(SearchTurn::text))
        assertEquals("", viewModel.state.value.query)
        assertEquals("gym receipt", interpreter.calls.single().accumulated)
        assertEquals("gym receipt", interpreter.calls.single().current)
        assertEquals(1, repository.planCalls)
        assertEquals(1L, store.saved?.revision)
    }

    @Test
    fun secondDetailAccumulatesRawCluesAndReceivesPreviousCompactPlan() = runTest {
        val march = accepted("gym", date = "2026-03-01")
        val april = accepted("gym", date = "2026-04-01", amount = 200_000L)
        val interpreter = QueueInterpreter(march, april)
        val store = RecordingStore()
        val viewModel = viewModel(FakeSearchRepository(), interpreter, store)

        viewModel.onQueryChanged("gym receipt around March")
        viewModel.search()
        viewModel.onQueryChanged("actually April around Rp200.000")
        viewModel.search()
        advanceUntilIdle()

        assertEquals(2, interpreter.calls.size)
        assertEquals(march.validatedPlan, interpreter.calls[1].previous)
        assertEquals("actually April around Rp200.000", interpreter.calls[1].current)
        assertEquals(
            "gym receipt around March actually April around Rp200.000",
            interpreter.calls[1].accumulated,
        )
        assertEquals(april.validatedPlan, store.saved?.acceptedPlannerPlan)
        assertEquals(2, viewModel.state.value.turns.size)
    }

    @Test
    fun staleInferenceCannotExecuteOrReplaceNewerRevision() = runTest {
        val repository = FakeSearchRepository(
            planHandler = { plan -> listOf(result(if (plan.merchant == "Second") 2 else 1)) },
        )
        val interpreter = ControlledInterpreter()
        val viewModel = viewModel(repository, interpreter)

        viewModel.onQueryChanged("first receipt")
        viewModel.search()
        viewModel.onQueryChanged("second detail")
        viewModel.search()
        assertEquals(2, interpreter.gates.size)

        interpreter.gates[1].complete(accepted("second", merchant = "Second"))
        advanceUntilIdle()
        assertEquals(2L, viewModel.state.value.results.first().documentId)

        interpreter.gates[0].complete(accepted("first", merchant = "First"))
        advanceUntilIdle()
        assertEquals(2L, viewModel.state.value.results.first().documentId)
        assertEquals(1, repository.planCalls)
    }

    @Test
    fun refinedReorderWaitsUntilScrollingStops() = runTest {
        val repository = FakeSearchRepository(
            rawResults = listOf(result(1), result(2)),
            planResults = listOf(result(2), result(1)),
        )
        val interpreter = ControlledInterpreter()
        val viewModel = viewModel(repository, interpreter)
        viewModel.onQueryChanged("receipt")
        viewModel.search()
        viewModel.onResultInteractionChanged(true)

        interpreter.gates.single().complete(accepted("receipt"))
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L), viewModel.state.value.results.map(SearchResult::documentId))
        assertTrue(viewModel.state.value.hasPendingReorder)

        viewModel.onResultInteractionChanged(false)
        assertEquals(listOf(2L, 1L), viewModel.state.value.results.map(SearchResult::documentId))
        assertFalse(viewModel.state.value.hasPendingReorder)
    }

    @Test
    fun exactPreliminaryCandidateStaysAheadOfSemanticBroadening() = runTest {
        val exact = result(1, EvidenceStrength.EXACT)
        val repository = FakeSearchRepository(rawResults = listOf(exact), planResults = listOf(result(2)))
        val viewModel = viewModel(repository, QueueInterpreter(accepted("fitness")))
        viewModel.onQueryChanged("fitness receipt")

        viewModel.search()
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L), viewModel.state.value.results.map(SearchResult::documentId))
    }

    @Test
    fun rejectedRefinementPreservesPreliminaryResults() = runTest {
        val repository = FakeSearchRepository(rawResults = listOf(result(1)))
        val rejected = QueryInterpretation.Rejected(PlannerRejection.MALFORMED_JSON, SearchPlan())
        val viewModel = viewModel(repository, QueueInterpreter(rejected))
        viewModel.onQueryChanged("receipt")

        viewModel.search()
        advanceUntilIdle()

        assertEquals(SearchPhase.REFINEMENT_UNAVAILABLE, viewModel.state.value.phase)
        assertEquals(listOf(1L), viewModel.state.value.results.map(SearchResult::documentId))
        assertEquals(0, repository.planCalls)
    }

    @Test
    fun explicitRetryCanRefineThePreservedPreliminaryResults() = runTest {
        val repository = FakeSearchRepository(rawResults = listOf(result(1)), planResults = listOf(result(2)))
        val rejected = QueryInterpretation.Rejected(PlannerRejection.ENGINE_UNAVAILABLE, SearchPlan())
        val viewModel = viewModel(repository, QueueInterpreter(rejected, accepted("receipt")))
        viewModel.onQueryChanged("receipt")
        viewModel.search()
        advanceUntilIdle()

        viewModel.retryRefinement()
        advanceUntilIdle()

        assertEquals(SearchPhase.REFINED, viewModel.state.value.phase)
        assertEquals(listOf(2L, 1L), viewModel.state.value.results.map(SearchResult::documentId))
    }

    @Test
    fun relaunchRestoresLatestPlanAndShownCountWithoutNewModelCall() = runTest {
        val results = (1L..25L).map { result(it) }
        val accepted = accepted("receipt")
        val stored = StoredSearchSession(
            revision = 7L,
            turns = listOf(SearchTurn("receipt", 1L)),
            acceptedPlannerPlan = accepted.validatedPlan,
            acceptedSearchPlan = accepted.searchPlan,
            shownResultCount = 20,
        )
        val repository = FakeSearchRepository(rawResults = results, planResults = results.reversed())
        val interpreter = QueueInterpreter()

        val viewModel = viewModel(repository, interpreter, RecordingStore(stored))
        advanceUntilIdle()

        assertEquals(SearchPhase.REFINED, viewModel.state.value.phase)
        assertEquals(20, viewModel.state.value.results.size)
        assertEquals(7L, viewModel.state.value.revision)
        assertTrue(interpreter.calls.isEmpty())
        assertEquals(1, repository.planCalls)
    }

    @Test
    fun newSearchIsOneTapAndUndoRestoresTheActiveSearch() = runTest {
        val store = RecordingStore()
        val viewModel = viewModel(
            FakeSearchRepository(rawResults = listOf(result(1)), planResults = listOf(result(1))),
            QueueInterpreter(accepted("receipt")),
            store,
        )
        viewModel.onQueryChanged("receipt")
        viewModel.search()
        advanceUntilIdle()

        viewModel.newSearch()
        assertTrue(viewModel.state.value.turns.isEmpty())
        assertTrue(viewModel.state.value.canUndo)
        assertTrue(viewModel.state.value.focusRequestId > 0L)
        assertEquals(1, store.clearCalls)

        viewModel.undoNewSearch()
        advanceUntilIdle()
        assertEquals(listOf("receipt"), viewModel.state.value.turns.map(SearchTurn::text))
        assertEquals(listOf(1L), viewModel.state.value.results.map(SearchResult::documentId))
        assertFalse(viewModel.state.value.canUndo)
    }

    @Test
    fun sessionCodecRoundTripsValidatedStateAndRejectsUnsafeCorruption() {
        val codec = SearchSessionCodec()
        val accepted = accepted("receipt", merchant = "Toko Maju")
        val session = StoredSearchSession(
            revision = 3L,
            turns = listOf(SearchTurn("Toko Maju receipt", 1L)),
            acceptedPlannerPlan = accepted.validatedPlan,
            acceptedSearchPlan = accepted.searchPlan,
            shownResultCount = 20,
        )

        assertEquals(session, codec.decode(codec.encode(session)))
        assertNull(codec.decode("{\"revision\":1}"))
        assertNull(
            codec.decode(
                codec.encode(
                    session.copy(acceptedSearchPlan = accepted.searchPlan.copy(merchant = "content://private")),
                ),
            ),
        )
    }

    private fun viewModel(
        repository: FakeSearchRepository,
        interpreter: QueryInterpreter,
        store: SearchSessionStore = RecordingStore(),
    ) = SearchViewModel(
        searchRepository = repository,
        fileOpener = FakeFileOpener,
        queryInterpreter = interpreter,
        refinedSearchExecutor = SafeRefinedSearchExecutor(repository),
        sessionStore = store,
        clock = { 1L },
    )

    private class FakeSearchRepository(
        private val rawResults: List<SearchResult> = emptyList(),
        private val planResults: List<SearchResult> = emptyList(),
        private val planHandler: ((SearchPlan) -> List<SearchResult>)? = null,
    ) : SearchRepository {
        val rawQueries = mutableListOf<String>()
        var planCalls = 0

        override suspend fun search(rawQuery: String): List<SearchResult> {
            rawQueries += rawQuery
            return rawResults
        }

        override suspend fun search(plan: SearchPlan): List<SearchResult> {
            planCalls += 1
            return planHandler?.invoke(plan) ?: planResults
        }

        override suspend fun removeUnavailable(documentId: Long) = Unit
    }

    private class QueueInterpreter(vararg responses: QueryInterpretation) : QueryInterpreter {
        val calls = mutableListOf<Call>()
        private val responses = ArrayDeque(responses.toList())

        override suspend fun interpret(
            accumulatedDetail: String,
            previousPlan: ValidatedPlannerPlan?,
            currentDetail: String,
        ): QueryInterpretation {
            calls += Call(accumulatedDetail, previousPlan, currentDetail)
            return responses.removeFirst()
        }
    }

    private class ControlledInterpreter : QueryInterpreter {
        val gates = mutableListOf<CompletableDeferred<QueryInterpretation>>()

        override suspend fun interpret(
            accumulatedDetail: String,
            previousPlan: ValidatedPlannerPlan?,
            currentDetail: String,
        ): QueryInterpretation {
            val gate = CompletableDeferred<QueryInterpretation>()
            gates += gate
            return gate.await()
        }
    }

    private class RecordingStore(initial: StoredSearchSession? = null) : SearchSessionStore {
        private var initialValue = initial
        var saved: StoredSearchSession? = null
        var clearCalls = 0

        override suspend fun load(): StoredSearchSession? = initialValue.also { initialValue = null }
        override suspend fun save(session: StoredSearchSession) {
            saved = session
        }

        override suspend fun clear() {
            clearCalls += 1
            saved = null
        }
    }

    private object FakeFileOpener : OriginalFileOpener {
        override fun open(result: SearchResult) = FileOpenOutcome.Opened
    }

    private data class Call(
        val accumulated: String,
        val previous: ValidatedPlannerPlan?,
        val current: String,
    )

    companion object {
        private fun accepted(
            term: String,
            merchant: String? = null,
            date: String? = null,
            amount: Long? = null,
        ): QueryInterpretation.Accepted {
            val planner = ValidatedPlannerPlan(
                textTerms = listOf(term),
                merchant = merchant,
                transactionDateRange = date?.let { StringBounds(it, it) },
                amountRangeMinor = amount?.let { LongBounds(it, it) },
            )
            val search = SearchPlan(
                textTerms = listOf(term),
                merchant = merchant,
                dateFromIso = date,
                dateToIso = date,
                amountMinMinor = amount,
                amountMaxMinor = amount,
                receiptIntent = true,
            )
            return QueryInterpretation.Accepted(planner, search, SearchPlan(textTerms = listOf(term)))
        }

        private fun result(id: Long, strength: EvidenceStrength? = null) = SearchResult(
            documentId = id,
            contentUri = "content://demo/$id",
            displayName = "receipt-$id.jpg",
            mimeType = "image/jpeg",
            evidence = strength?.let { listOf(MatchEvidence("OCR", "receipt", it)) }.orEmpty(),
            stableRankKey = id.toString(),
        )
    }
}
