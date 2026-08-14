package com.sagesearch.android.search

import com.sagesearch.android.data.db.DocumentEntity
import com.sagesearch.android.model.EvidenceStrength
import com.sagesearch.android.model.MatchEvidence
import com.sagesearch.android.model.SearchPlan

data class CandidateEvidence(
    val visible: List<MatchEvidence>,
    val exactClueCount: Int,
    val exactFieldWeight: Int,
    val structuredMatchCount: Int,
    val interpretedMatchCount: Int,
)

object EvidenceBuilder {
    private const val MAX_VISIBLE_EVIDENCE = 3
    private const val MAX_VALUE_LENGTH = 100

    fun build(document: DocumentEntity, plan: SearchPlan): CandidateEvidence {
        val exactTerms = plan.textTerms.distinct()
        val filenameHits = exactTerms.filter { document.displayName.contains(it, ignoreCase = true) }
        val merchantHits = exactTerms.filter { document.merchant?.contains(it, ignoreCase = true) == true }
        val plannedMerchantMatch = plan.merchant?.let { requested ->
            document.merchant?.contains(requested, ignoreCase = true) == true
        } == true
        val dateTextHits = exactTerms.filter { document.transactionDateText?.contains(it, ignoreCase = true) == true }
        val ocrHits = exactTerms.filter { document.ocrText.contains(it, ignoreCase = true) }
        val exactClues = (filenameHits + merchantHits + dateTextHits + ocrHits).toSet()
        val visible = mutableListOf<MatchEvidence>()
        var exactWeight = 0

        if (filenameHits.isNotEmpty()) {
            visible += MatchEvidence("Filename", document.displayName, EvidenceStrength.EXACT)
            exactWeight += 6
        }
        if (merchantHits.isNotEmpty()) {
            document.merchant?.let { visible += MatchEvidence("Merchant", it, EvidenceStrength.EXACT) }
            exactWeight += 5
        }
        if (plannedMerchantMatch && visible.none { it.label == "Merchant" }) {
            document.merchant?.let { visible += MatchEvidence("Merchant", it, EvidenceStrength.EXACT) }
            exactWeight += 5
        }
        if (dateTextHits.isNotEmpty()) {
            document.transactionDateText?.let { visible += MatchEvidence("Date", it, EvidenceStrength.EXACT) }
            exactWeight += 4
        }
        if (ocrHits.isNotEmpty()) {
            matchingLine(document.ocrText, ocrHits)?.let {
                visible += MatchEvidence("Text", it, EvidenceStrength.EXACT)
            }
            exactWeight += 3
        }

        var structured = 0
        if (matchesDate(document, plan) && visible.none { it.label == "Date" }) {
            val storedDate = document.transactionDateText ?: document.transactionDateIso
            storedDate?.let { visible += MatchEvidence("Date", it, EvidenceStrength.STRUCTURED) }
            structured += 1
        }
        if (matchesAmount(document, plan)) {
            val storedAmount = document.amountText
                ?: listOfNotNull(document.currencyCode, document.amountMinor?.toString()).joinToString(" ")
            if (storedAmount.isNotBlank()) visible += MatchEvidence("Amount", storedAmount, EvidenceStrength.STRUCTURED)
            structured += 1
        }
        if (matchesCurrency(document, plan) && visible.none { it.label == "Amount" }) {
            document.currencyCode?.let { visible += MatchEvidence("Currency", it, EvidenceStrength.STRUCTURED) }
            structured += 1
        }

        var interpreted = 0
        val categoryHits = plan.categoryTerms.filter { term ->
            document.displayName.contains(term, ignoreCase = true) ||
                document.ocrText.contains(term, ignoreCase = true) ||
                document.merchant?.contains(term, ignoreCase = true) == true
        }
        if (categoryHits.isNotEmpty()) {
            matchingLine(document.ocrText, categoryHits)?.let {
                visible += MatchEvidence("Text", it, EvidenceStrength.INTERPRETED)
            }
            interpreted += categoryHits.distinct().size
        }
        if (plan.receiptIntent && (document.contentKind == "receipt" || document.contentKind == "mixed")) {
            visible += MatchEvidence("Type", document.contentKind.replaceFirstChar(Char::uppercase), EvidenceStrength.INTERPRETED)
            interpreted += 1
        }

        return CandidateEvidence(
            visible = visible.distinctBy { it.label to it.value }.take(MAX_VISIBLE_EVIDENCE),
            exactClueCount = exactClues.size + if (plannedMerchantMatch) 1 else 0,
            exactFieldWeight = exactWeight,
            structuredMatchCount = structured,
            interpretedMatchCount = interpreted,
        )
    }

    private fun matchesDate(document: DocumentEntity, plan: SearchPlan): Boolean {
        val value = document.transactionDateIso ?: return false
        val from = plan.dateFromIso ?: return false
        val to = plan.dateToIso ?: from
        return value in from..to
    }

    private fun matchesAmount(document: DocumentEntity, plan: SearchPlan): Boolean {
        val value = document.amountMinor ?: return false
        val minimum = plan.amountMinMinor ?: return false
        val maximum = plan.amountMaxMinor ?: minimum
        return value in minimum..maximum && matchesCurrency(document, plan)
    }

    private fun matchesCurrency(document: DocumentEntity, plan: SearchPlan): Boolean {
        val requested = plan.currencyCode ?: return true
        return document.currencyCode.equals(requested, ignoreCase = true)
    }

    private fun matchingLine(text: String, terms: List<String>): String? = text.lineSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .maxByOrNull { line -> terms.count { line.contains(it, ignoreCase = true) } }
        ?.take(MAX_VALUE_LENGTH)
}
