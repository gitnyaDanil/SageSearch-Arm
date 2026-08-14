package com.sagesearch.android.index

import com.sagesearch.android.data.db.DocumentEntity
import com.sagesearch.android.model.AnalysisStatus

data class SourceScanResult(
    val accessible: Boolean,
    val discoveredDocuments: Int,
)

data class IndexProgress(
    val completedInBatch: Int,
    val remainingDocuments: Int,
)

sealed interface IndexBatchOutcome {
    data class Complete(val progress: IndexProgress) : IndexBatchOutcome
    data class MoreWork(val progress: IndexProgress) : IndexBatchOutcome
    data class Retry(val progress: IndexProgress) : IndexBatchOutcome
    data object AccessRevoked : IndexBatchOutcome
    data object Stopped : IndexBatchOutcome
}

interface IndexScheduler {
    fun enqueueRefresh(sourceId: Long)
    fun enqueueContinuation(sourceId: Long)
}

object NoOpIndexScheduler : IndexScheduler {
    override fun enqueueRefresh(sourceId: Long) = Unit
    override fun enqueueContinuation(sourceId: Long) = Unit
}

interface IndexSourceGateway {
    suspend fun refreshSourceForIndex(sourceId: Long): SourceScanResult
    suspend fun markNeedsAccess(sourceId: Long)
}

interface IndexDocumentRepository {
    suspend fun claimNext(sourceId: Long, claimedAtMillis: Long): DocumentEntity?
    suspend fun complete(
        document: DocumentEntity,
        checkpoint: DocumentCheckpoint,
        completedAtMillis: Long,
    ): Boolean
    suspend fun releaseClaim(documentId: Long): Boolean
    suspend fun recoverAbandoned(cutoffMillis: Long): Int
    suspend fun remaining(sourceId: Long): Int
    suspend fun updateSourceProgress(sourceId: Long, scannedAtMillis: Long)
}

data class DocumentCheckpoint(
    val status: AnalysisStatus,
    val ocrText: String = "",
    val contentKind: String = "metadata",
    val receiptConfidence: Double = 0.0,
    val merchant: String? = null,
    val transactionDateIso: String? = null,
    val transactionDateText: String? = null,
    val amountMinor: Long? = null,
    val amountText: String? = null,
    val currencyCode: String? = null,
    val extractionVersion: Int = 1,
)

fun interface IndexCheckpointProcessor {
    suspend fun process(document: DocumentEntity): DocumentCheckpoint
}

class RetryableIndexException : Exception()

class MetadataCheckpointProcessor : IndexCheckpointProcessor {
    override suspend fun process(document: DocumentEntity) = DocumentCheckpoint(
        status = AnalysisStatus.METADATA_INDEXED,
    )
}
