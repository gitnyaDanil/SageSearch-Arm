package com.sagesearch.android.model

enum class EvidenceStrength { EXACT, STRUCTURED, INTERPRETED }

data class MatchEvidence(
    val label: String,
    val value: String,
    val strength: EvidenceStrength,
)

data class SearchResult(
    val documentId: Long,
    val contentUri: String,
    val displayName: String,
    val mimeType: String,
    val evidence: List<MatchEvidence>,
    val stableRankKey: String,
)
