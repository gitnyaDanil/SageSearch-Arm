package com.sagesearch.android.ui.search

import com.sagesearch.android.model.SearchPhase
import com.sagesearch.android.model.SearchResult
import com.sagesearch.android.model.SearchTurn

data class SearchUiState(
    val revision: Long = 0L,
    val query: String = "",
    val turns: List<SearchTurn> = emptyList(),
    val phase: SearchPhase = SearchPhase.READY,
    val results: List<SearchResult> = emptyList(),
    val totalResultCount: Int = 0,
    val shownResultCount: Int = 0,
    val isRestoring: Boolean = false,
    val isInteracting: Boolean = false,
    val hasPendingReorder: Boolean = false,
    val canUndo: Boolean = false,
    val focusRequestId: Long = 0L,
    val message: String? = null,
) {
    val isSearching: Boolean get() = phase == SearchPhase.SEARCHING_PRELIMINARY
    val hasSearched: Boolean get() = turns.isNotEmpty()
    val canShowMore: Boolean get() = shownResultCount < totalResultCount
    val inputLabel: String get() = if (hasSearched) "Add another detail" else "What do you remember?"
}
