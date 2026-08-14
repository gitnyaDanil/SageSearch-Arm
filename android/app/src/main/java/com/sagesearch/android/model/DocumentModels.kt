package com.sagesearch.android.model

enum class SourceKind { TREE, INDIVIDUAL_FILE }

enum class SourceStatus { READY, INDEXING, PARTIAL, NEEDS_ACCESS, FAILED }

enum class AnalysisStatus {
    DISCOVERED,
    QUEUED,
    ANALYZING,
    METADATA_INDEXED,
    INDEXED,
    PARTIALLY_INDEXED,
    UNSUPPORTED_CONTENT,
    NEEDS_ACCESS,
    FAILED,
}

data class ApprovedSource(
    val id: Long,
    val uri: String,
    val label: String,
    val kind: SourceKind,
    val status: SourceStatus,
    val discoveredCount: Int,
    val indexedCount: Int,
    val lastScannedAtMillis: Long?,
)

data class IndexedDocument(
    val id: Long,
    val sourceId: Long,
    val contentUri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val modifiedAtMillis: Long?,
    val analyzedAtMillis: Long,
    val analysisStatus: AnalysisStatus,
    val receiptConfidence: Double,
    val ocrText: String,
    val contentKind: String,
    val merchant: String?,
    val transactionDateIso: String?,
    val transactionDateText: String?,
    val amountMinor: Long?,
    val amountText: String?,
    val currencyCode: String?,
    val extractionVersion: Int,
)
