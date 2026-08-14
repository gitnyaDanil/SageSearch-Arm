package com.sagesearch.android.planner

import com.sagesearch.android.model.SearchPlan
import java.util.Locale

class ValidatedPlanMapper {
    fun merge(preliminary: SearchPlan, refined: ValidatedPlannerPlan): SearchPlan {
        val transactionRange = refined.transactionDateRange
        val amountRange = refined.amountRangeMinor
        return SearchPlan(
            schemaVersion = 1,
            textTerms = distinct(preliminary.textTerms + refined.textTerms + listOfNotNull(refined.albumHint)),
            categoryTerms = distinct(preliminary.categoryTerms + refined.labels),
            merchant = refined.merchant ?: preliminary.merchant,
            dateFromIso = transactionRange?.start ?: transactionRange?.end ?: preliminary.dateFromIso,
            dateToIso = transactionRange?.end ?: transactionRange?.start ?: preliminary.dateToIso,
            amountMinMinor = amountRange?.minimum ?: amountRange?.maximum?.let { 0L }
                ?: preliminary.amountMinMinor,
            amountMaxMinor = amountRange?.maximum ?: amountRange?.minimum?.let { Long.MAX_VALUE }
                ?: preliminary.amountMaxMinor,
            currencyCode = refined.currencyCode ?: preliminary.currencyCode,
            receiptIntent = preliminary.receiptIntent || "receipt" in refined.contentKinds,
        )
    }

    private fun distinct(values: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        return values.filter { seen.add(it.lowercase(Locale.ROOT)) }.take(MAX_EXECUTABLE_TERMS)
    }

    companion object {
        private const val MAX_EXECUTABLE_TERMS = 12
    }
}
