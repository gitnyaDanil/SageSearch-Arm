package com.sagesearch.android.index

import com.sagesearch.android.data.db.SageSearchDatabase
import com.sagesearch.android.model.AnalysisStatus
import com.sagesearch.android.model.SourceStatus

class RoomIndexDocumentRepository(
    private val database: SageSearchDatabase,
) : IndexDocumentRepository {
    override suspend fun claimNext(sourceId: Long, claimedAtMillis: Long) =
        database.documentDao().claimNextQueued(sourceId, claimedAtMillis)

    override suspend fun complete(
        document: com.sagesearch.android.data.db.DocumentEntity,
        checkpoint: DocumentCheckpoint,
        completedAtMillis: Long,
    ): Boolean {
        val completed = document.copy(
            analyzedAtMillis = completedAtMillis,
            analysisStatus = checkpoint.status.name,
            receiptConfidence = checkpoint.receiptConfidence,
            ocrText = checkpoint.ocrText,
            contentKind = checkpoint.contentKind,
            merchant = checkpoint.merchant,
            transactionDateIso = checkpoint.transactionDateIso,
            transactionDateText = checkpoint.transactionDateText,
            amountMinor = checkpoint.amountMinor,
            amountText = checkpoint.amountText,
            currencyCode = checkpoint.currencyCode,
            extractionVersion = checkpoint.extractionVersion,
        )
        return database.documentDao().completeWithFts(completed, completed.searchableText())
    }

    override suspend fun releaseClaim(documentId: Long): Boolean =
        database.documentDao().releaseClaim(documentId) == 1

    override suspend fun recoverAbandoned(cutoffMillis: Long): Int =
        database.documentDao().recoverAbandoned(cutoffMillis)

    override suspend fun remaining(sourceId: Long): Int =
        database.documentDao().pendingCountForSource(sourceId)

    override suspend fun updateSourceProgress(sourceId: Long, scannedAtMillis: Long) {
        val discovered = database.documentDao().countForSource(sourceId)
        val completed = database.documentDao().completedCountForSource(sourceId)
        val pending = database.documentDao().pendingCountForSource(sourceId)
        database.approvedSourceDao().updateProgress(
            sourceId = sourceId,
            status = if (pending > 0) SourceStatus.INDEXING.name else SourceStatus.READY.name,
            discoveredCount = discovered,
            indexedCount = completed,
            scannedAtMillis = scannedAtMillis,
        )
    }
}

private fun com.sagesearch.android.data.db.DocumentEntity.searchableText(): String = listOfNotNull(
    displayName,
    ocrText.takeIf(String::isNotBlank),
    merchant,
    transactionDateIso,
    transactionDateText,
    amountText,
    currencyCode,
).joinToString(" ")
