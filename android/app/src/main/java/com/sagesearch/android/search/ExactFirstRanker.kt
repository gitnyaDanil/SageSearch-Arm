package com.sagesearch.android.search

import com.sagesearch.android.data.db.DocumentEntity
import com.sagesearch.android.model.SearchPlan
import com.sagesearch.android.model.SearchResult

object ExactFirstRanker {
    fun rank(candidates: Collection<DocumentEntity>, plan: SearchPlan): List<SearchResult> = candidates
        .map { document -> RankedDocument(document, EvidenceBuilder.build(document, plan)) }
        .filter { ranked ->
            ranked.evidence.exactClueCount > 0 ||
                ranked.evidence.structuredMatchCount > 0 ||
                ranked.evidence.interpretedMatchCount > 0
        }
        .sortedWith(
            compareByDescending<RankedDocument> {
                it.evidence.exactClueCount + it.evidence.structuredMatchCount
            }.thenByDescending { it.evidence.exactClueCount }
                .thenByDescending { it.evidence.exactFieldWeight }
                .thenByDescending { it.evidence.structuredMatchCount }
                .thenByDescending { it.evidence.interpretedMatchCount }
                .thenByDescending { it.document.modifiedAtMillis ?: 0L }
                .thenByDescending { it.document.id },
        )
        .map { ranked ->
            val document = ranked.document
            SearchResult(
                documentId = document.id,
                contentUri = document.contentUri,
                displayName = document.displayName,
                mimeType = document.mimeType,
                evidence = ranked.evidence.visible,
                stableRankKey = listOf(
                    ranked.evidence.exactClueCount,
                    ranked.evidence.exactFieldWeight,
                    ranked.evidence.structuredMatchCount,
                    ranked.evidence.interpretedMatchCount,
                    document.modifiedAtMillis ?: 0L,
                    document.id,
                ).joinToString(":"),
            )
        }

    private data class RankedDocument(
        val document: DocumentEntity,
        val evidence: CandidateEvidence,
    )
}
