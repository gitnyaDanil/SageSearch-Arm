package com.sagesearch.android.model

enum class SearchPhase { READY, SEARCHING_PRELIMINARY, REFINING_ON_DEVICE, REFINED, REFINEMENT_UNAVAILABLE }

data class SearchTurn(
    val text: String,
    val submittedAtMillis: Long,
)

data class SearchSession(
    val revision: Long,
    val turns: List<SearchTurn>,
    val acceptedPlan: SearchPlan?,
    val phase: SearchPhase,
    val shownResultCount: Int,
)
