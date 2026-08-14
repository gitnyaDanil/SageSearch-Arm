package com.sagesearch.android

import com.sagesearch.android.data.db.DocumentEntity
import com.sagesearch.android.data.storage.FileOpenOutcome
import com.sagesearch.android.data.storage.SafeOriginalFileOpener
import com.sagesearch.android.model.AnalysisStatus
import com.sagesearch.android.model.EvidenceStrength
import com.sagesearch.android.model.SearchPlan
import com.sagesearch.android.model.SearchResult
import com.sagesearch.android.search.DeterministicQueryParser
import com.sagesearch.android.search.EvidenceBuilder
import com.sagesearch.android.search.ExactFirstRanker
import com.sagesearch.android.search.FtsQueryBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicSearchTest {
    @Test
    fun parserExtractsTermsDateApproximateIdrAndReceiptIntent() {
        val plan = DeterministicQueryParser().parse(
            "Find my gym receipt 12/03/2026 around IDR 200.000",
        )

        assertTrue(plan.textTerms.containsAll(listOf("gym", "receipt")))
        assertEquals("2026-03-12", plan.dateFromIso)
        assertEquals("2026-03-12", plan.dateToIso)
        assertEquals(180_000L, plan.amountMinMinor)
        assertEquals(220_000L, plan.amountMaxMinor)
        assertEquals("IDR", plan.currencyCode)
        assertTrue(plan.receiptIntent)
    }

    @Test
    fun emptyInputProducesNoLoadAllPlan() {
        assertEquals(SearchPlan(), DeterministicQueryParser().parse("   "))
    }

    @Test
    fun ftsBuilderQuotesOperatorsWildcardsAndPunctuationAsLiteralPhrase() {
        assertEquals("\"shoe repair\"", FtsQueryBuilder.exactTerm("shoe repair"))
        assertEquals("\"foo or bar\"", FtsQueryBuilder.exactTerm("foo* OR \"bar\""))
        assertNull(FtsQueryBuilder.exactTerm("***"))
    }

    @Test
    fun multipleExactCluesOutrankOneExactClueAndBroadReceiptIntent() {
        val plan = SearchPlan(textTerms = listOf("gym", "membership"), receiptIntent = true)
        val multiple = document(
            id = 1,
            name = "IMG_opaque.jpg",
            ocr = "Fitness gym membership",
            kind = "receipt",
        )
        val single = document(
            id = 2,
            name = "gym-photo.jpg",
            ocr = "unrelated text",
            kind = "picture",
        )
        val broad = document(
            id = 3,
            name = "IMG_other.jpg",
            ocr = "TOTAL Rp 10.000",
            kind = "receipt",
        )

        val ranked = ExactFirstRanker.rank(listOf(broad, single, multiple), plan)

        assertEquals(listOf(1L, 2L, 3L), ranked.map(SearchResult::documentId))
    }

    @Test
    fun evidenceUsesAtMostThreeStoredFacts() {
        val document = document(
            id = 1,
            name = "gym-membership.jpg",
            ocr = "FITNESS CENTER\nGym membership\nTOTAL Rp 200.000",
            kind = "receipt",
            merchant = "Fitness Center",
            dateIso = "2026-03-12",
            dateText = "12 March 2026",
            amountMinor = 200_000,
            amountText = "Rp 200.000",
            currency = "IDR",
        )
        val evidence = EvidenceBuilder.build(
            document,
            SearchPlan(
                textTerms = listOf("gym", "membership"),
                dateFromIso = "2026-03-12",
                dateToIso = "2026-03-12",
                amountMinMinor = 200_000,
                amountMaxMinor = 200_000,
                currencyCode = "IDR",
                receiptIntent = true,
            ),
        ).visible

        assertTrue(evidence.size <= 3)
        assertTrue(evidence.all { item ->
            item.value in listOf(
                document.displayName,
                document.merchant,
                document.transactionDateText,
                document.transactionDateIso,
                document.amountText,
                document.currencyCode,
                "Gym membership",
                "Receipt",
            )
        })
        assertTrue(evidence.any { it.strength == EvidenceStrength.EXACT })
    }

    @Test
    fun fileOpenerMapsMissingOrDeniedOriginalToUnavailable() {
        val result = searchResult(1)
        assertEquals(FileOpenOutcome.Opened, SafeOriginalFileOpener { _, _ -> true }.open(result))
        assertEquals(FileOpenOutcome.Unavailable, SafeOriginalFileOpener { _, _ -> false }.open(result))
        assertEquals(
            FileOpenOutcome.Unavailable,
            SafeOriginalFileOpener { _, _ -> throw SecurityException() }.open(result),
        )
    }

    private fun searchResult(id: Long) = SearchResult(
        documentId = id,
        contentUri = "content://test/$id",
        displayName = "file-$id",
        mimeType = "image/jpeg",
        evidence = emptyList(),
        stableRankKey = id.toString(),
    )

    private fun document(
        id: Long,
        name: String,
        ocr: String,
        kind: String,
        merchant: String? = null,
        dateIso: String? = null,
        dateText: String? = null,
        amountMinor: Long? = null,
        amountText: String? = null,
        currency: String? = null,
    ) = DocumentEntity(
        id = id,
        sourceId = 1,
        contentUri = "content://test/$id",
        displayName = name,
        mimeType = "image/jpeg",
        sizeBytes = 100,
        modifiedAtMillis = id,
        analyzedAtMillis = id,
        analysisStatus = AnalysisStatus.INDEXED.name,
        receiptConfidence = if (kind == "receipt") 0.9 else 0.0,
        ocrText = ocr,
        contentKind = kind,
        merchant = merchant,
        transactionDateIso = dateIso,
        transactionDateText = dateText,
        amountMinor = amountMinor,
        amountText = amountText,
        currencyCode = currency,
        extractionVersion = 1,
    )
}
