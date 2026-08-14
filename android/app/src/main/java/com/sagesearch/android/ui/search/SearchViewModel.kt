package com.sagesearch.android.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sagesearch.android.data.storage.FileOpenOutcome
import com.sagesearch.android.data.storage.OriginalFileOpener
import com.sagesearch.android.model.SearchPhase
import com.sagesearch.android.model.SearchPlan
import com.sagesearch.android.model.SearchResult
import com.sagesearch.android.model.SearchTurn
import com.sagesearch.android.model.EvidenceStrength
import com.sagesearch.android.planner.PlannerRejection
import com.sagesearch.android.planner.QueryInterpretation
import com.sagesearch.android.planner.QueryInterpreter
import com.sagesearch.android.planner.SafeRefinedSearchExecutor
import com.sagesearch.android.planner.ValidatedPlannerPlan
import com.sagesearch.android.search.SearchRepository
import com.sagesearch.android.search.session.NoOpSearchSessionStore
import com.sagesearch.android.search.session.SearchSessionCodec
import com.sagesearch.android.search.session.SearchSessionStore
import com.sagesearch.android.search.session.StoredSearchSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SearchViewModel(
    private val searchRepository: SearchRepository,
    private val fileOpener: OriginalFileOpener,
    private val queryInterpreter: QueryInterpreter = UnavailableQueryInterpreter,
    private val refinedSearchExecutor: SafeRefinedSearchExecutor = SafeRefinedSearchExecutor(searchRepository),
    private val sessionStore: SearchSessionStore = NoOpSearchSessionStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private var rankedResults: List<SearchResult> = emptyList()
    private var pendingRankedResults: List<SearchResult>? = null
    private var activeRevision = 0L
    private var acceptedPlannerPlan: ValidatedPlannerPlan? = null
    private var acceptedSearchPlan: SearchPlan? = null
    private var persistenceJob: Job? = null
    private var undoExpiryJob: Job? = null
    private var undoSnapshot: UndoSnapshot? = null
    private val mutableState = MutableStateFlow(SearchUiState(isRestoring = true))
    val state: StateFlow<SearchUiState> = mutableState.asStateFlow()

    init {
        restoreLatest()
    }

    fun onQueryChanged(query: String) {
        mutableState.update { it.copy(query = query, message = null) }
    }

    fun search() {
        val detail = normalizeDetail(mutableState.value.query)
        if (detail.isEmpty()) {
            mutableState.update { it.copy(message = "Describe the file you remember.") }
            return
        }
        if (detail.length > SearchSessionCodec.MAX_TURN_CHARACTERS) {
            mutableState.update { it.copy(message = "Keep each detail under 200 characters.") }
            return
        }
        val existingTurns = mutableState.value.turns
        if (existingTurns.size >= SearchSessionCodec.MAX_TURNS) {
            mutableState.update { it.copy(message = "Start a new search before adding more details.") }
            return
        }
        val turns = existingTurns + SearchTurn(detail, clock())
        if (accumulated(turns).length > SearchSessionCodec.MAX_ACCUMULATED_CHARACTERS) {
            mutableState.update { it.copy(message = "This search is full. Start a new search to continue.") }
            return
        }

        clearUndo()
        activeRevision += 1L
        val revision = activeRevision
        val targetShown = mutableState.value.shownResultCount.coerceAtLeast(PAGE_SIZE)
        val previousPlan = acceptedPlannerPlan
        pendingRankedResults = null
        mutableState.update {
            it.copy(
                revision = revision,
                query = "",
                turns = turns,
                phase = SearchPhase.SEARCHING_PRELIMINARY,
                isRestoring = false,
                hasPendingReorder = false,
                canUndo = false,
                message = null,
            )
        }
        schedulePersist(revision)
        viewModelScope.launch {
            runSubmission(revision, turns, detail, previousPlan, targetShown)
        }
    }

    fun showMore() {
        val shown = (mutableState.value.shownResultCount + PAGE_SIZE).coerceAtMost(rankedResults.size)
        mutableState.update {
            it.copy(results = rankedResults.take(shown), shownResultCount = shown)
        }
        schedulePersist(activeRevision)
    }

    fun retryRefinement() {
        val turns = mutableState.value.turns
        if (turns.isEmpty() || mutableState.value.phase != SearchPhase.REFINEMENT_UNAVAILABLE) return
        activeRevision += 1L
        val revision = activeRevision
        mutableState.update {
            it.copy(
                revision = revision,
                phase = SearchPhase.REFINING_ON_DEVICE,
                message = null,
            )
        }
        schedulePersist(revision)
        viewModelScope.launch {
            refine(
                revision = revision,
                turns = turns,
                currentDetail = turns.last().text,
                previousPlan = acceptedPlannerPlan,
                preliminary = rankedResults,
                targetShown = mutableState.value.shownResultCount.coerceAtLeast(PAGE_SIZE),
            )
        }
    }

    fun onResultInteractionChanged(isInteracting: Boolean) {
        mutableState.update { it.copy(isInteracting = isInteracting) }
        if (isInteracting) return
        val pending = pendingRankedResults ?: return
        pendingRankedResults = null
        commitRanking(
            results = pending,
            targetShown = mutableState.value.shownResultCount.coerceAtLeast(PAGE_SIZE),
            phase = SearchPhase.REFINED,
            message = null,
        )
        schedulePersist(activeRevision)
    }

    fun openFile(result: SearchResult) {
        if (fileOpener.open(result) == FileOpenOutcome.Opened) return
        rankedResults = rankedResults.filterNot { it.documentId == result.documentId }
        pendingRankedResults = pendingRankedResults?.filterNot { it.documentId == result.documentId }
        mutableState.update { current ->
            val shown = current.shownResultCount.coerceAtMost(rankedResults.size)
            current.copy(
                results = rankedResults.take(shown),
                totalResultCount = rankedResults.size,
                shownResultCount = shown,
                message = "The original file is no longer available.",
            )
        }
        viewModelScope.launch { runCatching { searchRepository.removeUnavailable(result.documentId) } }
        schedulePersist(activeRevision)
    }

    fun newSearch() {
        val current = mutableState.value
        undoSnapshot = if (current.turns.isNotEmpty() || current.query.isNotBlank()) {
            UndoSnapshot(currentSession(), current.query, rankedResults)
        } else {
            null
        }
        activeRevision += 1L
        acceptedPlannerPlan = null
        acceptedSearchPlan = null
        rankedResults = emptyList()
        pendingRankedResults = null
        persistenceJob?.cancel()
        mutableState.value = SearchUiState(
            revision = activeRevision,
            canUndo = undoSnapshot != null,
            focusRequestId = current.focusRequestId + 1L,
            message = undoSnapshot?.let { "Search cleared." },
        )
        viewModelScope.launch { runCatching { sessionStore.clear() } }
        undoExpiryJob?.cancel()
        if (undoSnapshot != null) {
            undoExpiryJob = viewModelScope.launch {
                delay(UNDO_WINDOW_MILLIS)
                undoSnapshot = null
                mutableState.update { it.copy(canUndo = false) }
            }
        }
    }

    fun undoNewSearch() {
        val snapshot = undoSnapshot ?: return
        clearUndo()
        activeRevision += 1L
        val revision = activeRevision
        acceptedPlannerPlan = snapshot.session.acceptedPlannerPlan
        acceptedSearchPlan = snapshot.session.acceptedSearchPlan
        rankedResults = snapshot.results
        mutableState.value = SearchUiState(
            revision = revision,
            query = snapshot.draft,
            turns = snapshot.session.turns,
            phase = if (snapshot.session.turns.isEmpty()) SearchPhase.READY else SearchPhase.SEARCHING_PRELIMINARY,
            results = snapshot.results.take(snapshot.session.shownResultCount),
            totalResultCount = snapshot.results.size,
            shownResultCount = snapshot.session.shownResultCount.coerceAtMost(snapshot.results.size),
            focusRequestId = mutableState.value.focusRequestId + 1L,
        )
        if (snapshot.session.turns.isEmpty()) return
        schedulePersist(revision)
        viewModelScope.launch {
            restoreResults(snapshot.session.copy(revision = revision), revision)
        }
    }

    private fun restoreLatest() {
        viewModelScope.launch {
            val restored = runCatching { sessionStore.load() }.getOrNull()
            if (restored == null) {
                mutableState.update { it.copy(isRestoring = false) }
                return@launch
            }
            activeRevision = restored.revision
            acceptedPlannerPlan = restored.acceptedPlannerPlan
            acceptedSearchPlan = restored.acceptedSearchPlan
            mutableState.value = SearchUiState(
                revision = restored.revision,
                turns = restored.turns,
                phase = SearchPhase.SEARCHING_PRELIMINARY,
                shownResultCount = restored.shownResultCount,
                isRestoring = true,
            )
            restoreResults(restored, restored.revision)
        }
    }

    private suspend fun restoreResults(session: StoredSearchSession, revision: Long) {
        val preliminary = runCatching { searchRepository.search(session.accumulatedDetail) }.getOrElse {
            if (isCurrent(revision)) {
                mutableState.update {
                    it.copy(
                        phase = SearchPhase.REFINEMENT_UNAVAILABLE,
                        isRestoring = false,
                        message = "Local search needs attention.",
                    )
                }
            }
            return
        }
        if (!isCurrent(revision)) return
        commitRanking(preliminary, session.shownResultCount, SearchPhase.REFINING_ON_DEVICE, null)
        val plannerPlan = session.acceptedPlannerPlan
        val searchPlan = session.acceptedSearchPlan
        if (plannerPlan != null && searchPlan != null) {
            val accepted = QueryInterpretation.Accepted(plannerPlan, searchPlan, SearchPlan())
            val refined = runCatching { refinedSearchExecutor.execute(accepted) }.getOrNull()
            if (!isCurrent(revision)) return
            val finalResults = refined?.let { mergeRefined(preliminary, it) } ?: preliminary
            commitRanking(finalResults, session.shownResultCount, SearchPhase.REFINED, null)
            mutableState.update { it.copy(isRestoring = false) }
            return
        }
        mutableState.update { it.copy(isRestoring = false) }
        refine(revision, session.turns, session.turns.last().text, null, preliminary, session.shownResultCount)
    }

    private suspend fun runSubmission(
        revision: Long,
        turns: List<SearchTurn>,
        currentDetail: String,
        previousPlan: ValidatedPlannerPlan?,
        targetShown: Int,
    ) {
        val preliminary = runCatching { searchRepository.search(accumulated(turns)) }.getOrElse {
            if (isCurrent(revision)) {
                mutableState.update {
                    it.copy(
                        phase = SearchPhase.REFINEMENT_UNAVAILABLE,
                        message = "Local search needs attention.",
                    )
                }
            }
            return
        }
        if (!isCurrent(revision)) return
        commitRanking(preliminary, targetShown, SearchPhase.REFINING_ON_DEVICE, null)
        schedulePersist(revision)
        refine(revision, turns, currentDetail, previousPlan, preliminary, targetShown)
    }

    private suspend fun refine(
        revision: Long,
        turns: List<SearchTurn>,
        currentDetail: String,
        previousPlan: ValidatedPlannerPlan?,
        preliminary: List<SearchResult>,
        targetShown: Int,
    ) {
        val interpretation = runCatching {
            queryInterpreter.interpret(
                accumulatedDetail = accumulated(turns),
                previousPlan = previousPlan,
                currentDetail = currentDetail,
            )
        }.getOrElse {
            if (isCurrent(revision)) refinementUnavailable(revision)
            return
        }
        if (!isCurrent(revision)) return
        when (interpretation) {
            is QueryInterpretation.Rejected -> refinementUnavailable(revision)
            is QueryInterpretation.Accepted -> {
                val refined = runCatching { refinedSearchExecutor.execute(interpretation) }.getOrNull()
                if (!isCurrent(revision)) return
                if (refined == null) {
                    refinementUnavailable(revision)
                    return
                }
                acceptedPlannerPlan = interpretation.validatedPlan
                acceptedSearchPlan = interpretation.searchPlan
                val finalResults = mergeRefined(preliminary, refined)
                if (mutableState.value.isInteracting && orderChanged(rankedResults, finalResults)) {
                    pendingRankedResults = finalResults
                    mutableState.update {
                        it.copy(
                            phase = SearchPhase.REFINED,
                            hasPendingReorder = true,
                            message = "Refinement ready. Results will reorder when scrolling stops.",
                        )
                    }
                } else {
                    commitRanking(finalResults, targetShown, SearchPhase.REFINED, null)
                }
                schedulePersist(revision)
            }
        }
    }

    private fun refinementUnavailable(revision: Long) {
        if (!isCurrent(revision)) return
        mutableState.update {
            it.copy(
                phase = SearchPhase.REFINEMENT_UNAVAILABLE,
                message = "AI refinement is unavailable. Preliminary matches are still usable.",
            )
        }
        schedulePersist(revision)
    }

    private fun commitRanking(
        results: List<SearchResult>,
        targetShown: Int,
        phase: SearchPhase,
        message: String?,
    ) {
        rankedResults = results
        val shown = targetShown.coerceAtLeast(PAGE_SIZE).coerceAtMost(results.size)
        mutableState.update {
            it.copy(
                phase = phase,
                results = results.take(shown),
                totalResultCount = results.size,
                shownResultCount = shown,
                isRestoring = false,
                hasPendingReorder = false,
                message = message,
            )
        }
    }

    private fun mergeRefined(
        preliminary: List<SearchResult>,
        refined: List<SearchResult>,
    ): List<SearchResult> {
        val pinnedExact = preliminary.filter { result ->
            result.evidence.any { it.strength == EvidenceStrength.EXACT }
        }
        val seen = pinnedExact.mapTo(mutableSetOf(), SearchResult::documentId)
        val reordered = refined.filter { seen.add(it.documentId) }
        val retained = preliminary.filter { seen.add(it.documentId) }
        return pinnedExact + reordered + retained
    }

    private fun orderChanged(before: List<SearchResult>, after: List<SearchResult>): Boolean =
        before.map(SearchResult::documentId) != after.map(SearchResult::documentId)

    private fun schedulePersist(revision: Long) {
        if (!isCurrent(revision) || mutableState.value.turns.isEmpty()) return
        val snapshot = currentSession()
        persistenceJob?.cancel()
        persistenceJob = viewModelScope.launch {
            if (isCurrent(revision)) runCatching { sessionStore.save(snapshot) }
        }
    }

    private fun currentSession(): StoredSearchSession {
        val current = mutableState.value
        return StoredSearchSession(
            revision = current.revision.coerceAtLeast(1L),
            turns = current.turns,
            acceptedPlannerPlan = acceptedPlannerPlan,
            acceptedSearchPlan = acceptedSearchPlan,
            shownResultCount = current.shownResultCount.coerceAtLeast(PAGE_SIZE),
        )
    }

    private fun clearUndo() {
        undoExpiryJob?.cancel()
        undoExpiryJob = null
        undoSnapshot = null
    }

    private fun isCurrent(revision: Long): Boolean = activeRevision == revision

    private fun normalizeDetail(value: String): String = value
        .filterNot { it.isISOControl() && !it.isWhitespace() }
        .trim()

    private fun accumulated(turns: List<SearchTurn>): String =
        turns.joinToString(" ", transform = SearchTurn::text)

    private data class UndoSnapshot(
        val session: StoredSearchSession,
        val draft: String,
        val results: List<SearchResult>,
    )

    class Factory(
        private val searchRepository: SearchRepository,
        private val fileOpener: OriginalFileOpener,
        private val queryInterpreter: QueryInterpreter,
        private val refinedSearchExecutor: SafeRefinedSearchExecutor,
        private val sessionStore: SearchSessionStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SearchViewModel(
                searchRepository,
                fileOpener,
                queryInterpreter,
                refinedSearchExecutor,
                sessionStore,
            ) as T
    }

    companion object {
        const val PAGE_SIZE = 10
        private const val UNDO_WINDOW_MILLIS = 8_000L

        private object UnavailableQueryInterpreter : QueryInterpreter {
            override suspend fun interpret(
                accumulatedDetail: String,
                previousPlan: ValidatedPlannerPlan?,
                currentDetail: String,
            ): QueryInterpretation = QueryInterpretation.Rejected(
                PlannerRejection.ENGINE_UNAVAILABLE,
                SearchPlan(),
            )
        }
    }
}
