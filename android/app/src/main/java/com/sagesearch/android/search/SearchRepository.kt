package com.sagesearch.android.search

import com.sagesearch.android.data.db.DocumentEntity
import com.sagesearch.android.data.db.SageSearchDatabase
import com.sagesearch.android.model.SearchPlan
import com.sagesearch.android.model.SearchResult

interface SearchRepository {
    suspend fun search(rawQuery: String): List<SearchResult>
    suspend fun search(plan: SearchPlan): List<SearchResult>
    suspend fun removeUnavailable(documentId: Long)
}

class DefaultSearchRepository(
    private val database: SageSearchDatabase,
    private val parser: DeterministicQueryParser = DeterministicQueryParser(),
) : SearchRepository {
    override suspend fun search(rawQuery: String): List<SearchResult> {
        if (rawQuery.isBlank()) return emptyList()
        return search(parser.parse(rawQuery))
    }

    override suspend fun search(plan: SearchPlan): List<SearchResult> {
        if (plan.isEmpty()) return emptyList()
        val candidates = linkedMapOf<Long, CandidateAccumulator>()
        val dao = database.documentSearchDao()

        (plan.textTerms + plan.categoryTerms).distinct().forEach { term ->
            val matchQuery = FtsQueryBuilder.exactTerm(term) ?: return@forEach
            addCandidates(candidates, dao.searchFts(matchQuery, PER_SOURCE_LIMIT), "text:$term")
        }
        val from = plan.dateFromIso
        if (from != null) {
            addCandidates(
                candidates,
                dao.searchDateRange(from, plan.dateToIso ?: from, PER_SOURCE_LIMIT),
                "date",
            )
        }
        val minimum = plan.amountMinMinor
        if (minimum != null) {
            addCandidates(
                candidates,
                dao.searchAmountRange(
                    minimumMinor = minimum,
                    maximumMinor = plan.amountMaxMinor ?: minimum,
                    currencyCode = plan.currencyCode,
                    limit = PER_SOURCE_LIMIT,
                ),
                "amount",
            )
        }
        plan.currencyCode?.let { currency ->
            addCandidates(candidates, dao.searchCurrency(currency, PER_SOURCE_LIMIT), "currency")
        }
        plan.merchant?.takeIf(String::isNotBlank)?.let { merchant ->
            addCandidates(candidates, dao.searchMerchant(merchant, PER_SOURCE_LIMIT), "merchant")
        }
        if (plan.receiptIntent) {
            addCandidates(candidates, dao.searchReceiptCandidates(PER_SOURCE_LIMIT), "receipt")
        }

        val bounded = candidates.values
            .sortedWith(
                compareByDescending<CandidateAccumulator> { it.clues.size }
                    .thenByDescending { it.document.modifiedAtMillis ?: 0L }
                    .thenByDescending { it.document.id },
            )
            .take(MAX_CANDIDATES)
            .map(CandidateAccumulator::document)
        return ExactFirstRanker.rank(bounded, plan)
    }

    override suspend fun removeUnavailable(documentId: Long) {
        database.documentDao().removeUnavailable(documentId)
    }

    private fun addCandidates(
        target: MutableMap<Long, CandidateAccumulator>,
        documents: List<DocumentEntity>,
        clue: String,
    ) {
        documents.forEach { document ->
            target.getOrPut(document.id) { CandidateAccumulator(document) }.clues += clue
        }
    }

    private fun SearchPlan.isEmpty(): Boolean = textTerms.isEmpty() && categoryTerms.isEmpty() &&
        merchant == null && dateFromIso == null && dateToIso == null && amountMinMinor == null &&
        amountMaxMinor == null && currencyCode == null && !receiptIntent

    private data class CandidateAccumulator(
        val document: DocumentEntity,
        val clues: MutableSet<String> = mutableSetOf(),
    )

    companion object {
        const val MAX_CANDIDATES = 200
        private const val PER_SOURCE_LIMIT = 200
    }
}
