package com.sagesearch.android.search.session

import com.google.gson.Gson
import com.sagesearch.android.model.SearchPlan
import com.sagesearch.android.model.SearchTurn
import com.sagesearch.android.planner.PlanValidationResult
import com.sagesearch.android.planner.PlannerOutputValidator
import com.sagesearch.android.planner.PlannerPlanJson
import com.sagesearch.android.planner.ValidatedPlannerPlan

data class StoredSearchSession(
    val revision: Long,
    val turns: List<SearchTurn>,
    val acceptedPlannerPlan: ValidatedPlannerPlan?,
    val acceptedSearchPlan: SearchPlan?,
    val shownResultCount: Int,
) {
    val accumulatedDetail: String get() = turns.joinToString(" ", transform = SearchTurn::text)
}

interface SearchSessionStore {
    suspend fun load(): StoredSearchSession?
    suspend fun save(session: StoredSearchSession)
    suspend fun clear()
}

object NoOpSearchSessionStore : SearchSessionStore {
    override suspend fun load(): StoredSearchSession? = null
    override suspend fun save(session: StoredSearchSession) = Unit
    override suspend fun clear() = Unit
}

class SearchSessionCodec(
    private val gson: Gson = Gson(),
    private val validator: PlannerOutputValidator = PlannerOutputValidator(),
) {
    fun encode(session: StoredSearchSession): String = gson.toJson(session)

    fun decode(raw: String): StoredSearchSession? = runCatching { decodeChecked(raw) }.getOrNull()

    private fun decodeChecked(raw: String): StoredSearchSession? {
        val decoded = gson.fromJson(raw, StoredSearchSession::class.java) ?: return null
        if (decoded.revision < 1L || decoded.turns.isEmpty() || decoded.turns.size > MAX_TURNS) return null
        val turns = decoded.turns.map { turn ->
            val text = turn.text
                .filterNot { it.isISOControl() && !it.isWhitespace() }
                .trim()
            if (text.isEmpty() || text.length > MAX_TURN_CHARACTERS || turn.submittedAtMillis < 0L) return null
            SearchTurn(text, turn.submittedAtMillis)
        }
        if (turns.joinToString(" ", transform = SearchTurn::text).length > MAX_ACCUMULATED_CHARACTERS) {
            return null
        }
        val plannerPlan = decoded.acceptedPlannerPlan?.let { plan ->
            when (val checked = validator.validate(PlannerPlanJson.encode(plan))) {
                is PlanValidationResult.Valid -> checked.plan
                is PlanValidationResult.Invalid -> return null
            }
        }
        val searchPlan = decoded.acceptedSearchPlan?.takeIf(::isSafeSearchPlan) ?: run {
            if (decoded.acceptedSearchPlan != null) return null
            null
        }
        if ((plannerPlan == null) != (searchPlan == null)) return null
        return StoredSearchSession(
            revision = decoded.revision,
            turns = turns,
            acceptedPlannerPlan = plannerPlan,
            acceptedSearchPlan = searchPlan,
            shownResultCount = decoded.shownResultCount.coerceIn(PAGE_SIZE, MAX_SHOWN_RESULTS),
        )
    }

    private fun isSafeSearchPlan(plan: SearchPlan): Boolean {
        if (plan.schemaVersion != 1) return false
        val terms = plan.textTerms + plan.categoryTerms
        if (terms.size > MAX_TERMS || terms.any {
                it.isBlank() || it.length > MAX_TERM_CHARACTERS || UNSAFE_VALUE.containsMatchIn(it)
            }
        ) return false
        if (listOfNotNull(plan.amountMinMinor, plan.amountMaxMinor).any { it < 0L }) return false
        if (plan.amountMinMinor != null && plan.amountMaxMinor != null && plan.amountMinMinor > plan.amountMaxMinor) {
            return false
        }
        if (plan.currencyCode != null && !CURRENCY.matches(plan.currencyCode)) return false
        if (listOfNotNull(plan.dateFromIso, plan.dateToIso).any { !ISO_DATE.matches(it) }) return false
        return listOfNotNull(plan.merchant)
            .all { it.length <= MAX_FIELD_CHARACTERS && !UNSAFE_VALUE.containsMatchIn(it) }
    }

    companion object {
        const val MAX_TURNS = 8
        const val MAX_TURN_CHARACTERS = 200
        const val MAX_ACCUMULATED_CHARACTERS = 500
        private const val MAX_TERMS = 24
        private const val MAX_TERM_CHARACTERS = 64
        private const val MAX_FIELD_CHARACTERS = 120
        private const val PAGE_SIZE = 10
        private const val MAX_SHOWN_RESULTS = 200
        private val CURRENCY = Regex("[A-Z]{3}")
        private val ISO_DATE = Regex("20[0-9]{2}-[01][0-9]-[0-3][0-9]")
        private val UNSAFE_VALUE = Regex("(?i)(?:\\b(?:select|insert|update|delete|drop|alter)\\b|[*?]|://|[/\\\\])")
    }
}
