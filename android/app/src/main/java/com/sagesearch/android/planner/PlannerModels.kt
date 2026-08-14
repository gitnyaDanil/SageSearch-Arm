package com.sagesearch.android.planner

import com.sagesearch.android.model.SearchPlan
import com.sagesearch.android.model.SearchResult
import com.sagesearch.android.search.SearchRepository

data class LongBounds(val minimum: Long?, val maximum: Long?)
data class StringBounds(val start: String?, val end: String?)

data class ValidatedPlannerPlan(
    val version: Int = 1,
    val textTerms: List<String> = emptyList(),
    val contentKinds: List<String> = emptyList(),
    val merchant: String? = null,
    val amountRangeMinor: LongBounds? = null,
    val currencyCode: String? = null,
    val transactionDateRange: StringBounds? = null,
    val mediaDateRange: StringBounds? = null,
    val labels: List<String> = emptyList(),
    val faceFilter: String? = null,
    val albumHint: String? = null,
)

enum class PlannerRejection {
    OUTPUT_TOO_LARGE,
    MALFORMED_JSON,
    DUPLICATE_KEY,
    ROOT_NOT_OBJECT,
    UNKNOWN_FIELD,
    BAD_SCHEMA_VERSION,
    WRONG_TYPE,
    EMPTY_VALUE,
    TOO_MANY_VALUES,
    DUPLICATE_VALUE,
    UNSUPPORTED_VALUE,
    UNSAFE_VALUE,
    INVALID_RANGE,
    INVALID_DATE,
    INVALID_INSTANT,
    ENGINE_UNAVAILABLE,
}

sealed interface PlanValidationResult {
    data class Valid(val plan: ValidatedPlannerPlan) : PlanValidationResult
    data class Invalid(val reason: PlannerRejection) : PlanValidationResult
}

sealed interface QueryInterpretation {
    val fallbackPlan: SearchPlan

    data class Accepted(
        val validatedPlan: ValidatedPlannerPlan,
        val searchPlan: SearchPlan,
        override val fallbackPlan: SearchPlan,
    ) : QueryInterpretation

    data class Rejected(
        val reason: PlannerRejection,
        override val fallbackPlan: SearchPlan,
    ) : QueryInterpretation
}

interface QueryInterpreter {
    suspend fun interpret(
        accumulatedDetail: String,
        previousPlan: ValidatedPlannerPlan? = null,
        currentDetail: String = accumulatedDetail,
    ): QueryInterpretation
}

/** The only application path that may execute a model-refined plan. */
class SafeRefinedSearchExecutor(
    private val repository: SearchRepository,
) {
    suspend fun execute(interpretation: QueryInterpretation): List<SearchResult>? =
        when (interpretation) {
            is QueryInterpretation.Accepted -> repository.search(interpretation.searchPlan)
            is QueryInterpretation.Rejected -> null
        }
}
