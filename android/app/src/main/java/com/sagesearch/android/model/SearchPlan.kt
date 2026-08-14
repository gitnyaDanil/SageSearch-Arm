package com.sagesearch.android.model

data class SearchPlan(
    val schemaVersion: Int = 1,
    val textTerms: List<String> = emptyList(),
    val categoryTerms: List<String> = emptyList(),
    val merchant: String? = null,
    val dateFromIso: String? = null,
    val dateToIso: String? = null,
    val amountMinMinor: Long? = null,
    val amountMaxMinor: Long? = null,
    val currencyCode: String? = null,
    val receiptIntent: Boolean = false,
)
