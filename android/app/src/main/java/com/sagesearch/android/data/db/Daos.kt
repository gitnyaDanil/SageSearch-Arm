package com.sagesearch.android.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class DocumentCountProjection(
    val searchableDocumentCount: Int,
    val completedDocumentCount: Int,
    val pendingDocumentCount: Int,
)

@Dao
abstract class ApprovedSourceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertInternal(source: ApprovedSourceEntity): Long

    @Query("SELECT * FROM approved_sources WHERE uri = :uri LIMIT 1")
    protected abstract suspend fun findByUriInternal(uri: String): ApprovedSourceEntity?

    @Query("SELECT * FROM approved_sources WHERE id = :sourceId LIMIT 1")
    abstract suspend fun findById(sourceId: Long): ApprovedSourceEntity?

    @Query("SELECT * FROM approved_sources ORDER BY id")
    abstract suspend fun list(): List<ApprovedSourceEntity>

    @Query("SELECT * FROM approved_sources ORDER BY id")
    abstract fun observeList(): Flow<List<ApprovedSourceEntity>>

    @Update
    protected abstract suspend fun updateInternal(source: ApprovedSourceEntity)

    @Transaction
    open suspend fun upsert(source: ApprovedSourceEntity): Long {
        findByUriInternal(source.uri)?.let { existing ->
            updateInternal(source.copy(id = existing.id))
            return existing.id
        }
        val inserted = insertInternal(source)
        return if (inserted != -1L) inserted else requireNotNull(findByUriInternal(source.uri)).id
    }

    @Query("UPDATE approved_sources SET status = :status WHERE id = :sourceId")
    abstract suspend fun updateStatus(sourceId: Long, status: String)

    @Query(
        """
        UPDATE approved_sources
        SET status = :status,
            discoveredCount = :discoveredCount,
            indexedCount = :indexedCount,
            lastScannedAtMillis = :scannedAtMillis
        WHERE id = :sourceId
        """,
    )
    abstract suspend fun updateProgress(
        sourceId: Long,
        status: String,
        discoveredCount: Int,
        indexedCount: Int,
        scannedAtMillis: Long,
    )
}

@Dao
abstract class DocumentDao {
    @Insert
    protected abstract suspend fun insertDocumentInternal(document: DocumentEntity): Long

    @Update
    protected abstract suspend fun updateDocumentInternal(document: DocumentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun replaceFtsInternal(document: DocumentFtsEntity)

    @Query("DELETE FROM documents_fts WHERE rowid = :documentId")
    protected abstract suspend fun deleteFtsInternal(documentId: Long)

    @Query("DELETE FROM documents WHERE id = :documentId")
    protected abstract suspend fun deleteDocumentInternal(documentId: Long)

    @Transaction
    open suspend fun removeUnavailable(documentId: Long) = deleteWithFts(documentId)

    @Query("SELECT id FROM documents WHERE contentUri = :contentUri LIMIT 1")
    protected abstract suspend fun findIdByUriInternal(contentUri: String): Long?

    @Query("SELECT * FROM documents WHERE contentUri = :contentUri LIMIT 1")
    abstract suspend fun findByUri(contentUri: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE id = :documentId LIMIT 1")
    protected abstract suspend fun findByIdInternal(documentId: Long): DocumentEntity?

    @Query("SELECT * FROM documents ORDER BY id")
    abstract suspend fun list(): List<DocumentEntity>

    @Query("SELECT COUNT(*) FROM documents")
    abstract suspend fun count(): Int

    @Query(
        """
        SELECT COUNT(*) AS searchableDocumentCount,
               COALESCE(SUM(CASE WHEN analysisStatus NOT IN ('DISCOVERED', 'QUEUED', 'ANALYZING') THEN 1 ELSE 0 END), 0) AS completedDocumentCount,
               COALESCE(SUM(CASE WHEN analysisStatus IN ('QUEUED', 'ANALYZING') THEN 1 ELSE 0 END), 0) AS pendingDocumentCount
        FROM documents
        """,
    )
    abstract fun observeCounts(): Flow<DocumentCountProjection>

    @Query("SELECT COUNT(*) FROM documents WHERE sourceId = :sourceId")
    abstract suspend fun countForSource(sourceId: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM documents
        WHERE sourceId = :sourceId AND analysisStatus IN ('QUEUED', 'ANALYZING')
        """,
    )
    abstract suspend fun pendingCountForSource(sourceId: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM documents
        WHERE sourceId = :sourceId
          AND analysisStatus NOT IN ('DISCOVERED', 'QUEUED', 'ANALYZING')
        """,
    )
    abstract suspend fun completedCountForSource(sourceId: Long): Int

    @Query(
        """
        SELECT * FROM documents
        WHERE sourceId = :sourceId AND analysisStatus = 'QUEUED'
        ORDER BY id
        LIMIT 1
        """,
    )
    protected abstract suspend fun nextQueuedInternal(sourceId: Long): DocumentEntity?

    @Query(
        """
        UPDATE documents
        SET analysisStatus = 'ANALYZING', analyzedAtMillis = :claimedAtMillis
        WHERE id = :documentId AND analysisStatus = 'QUEUED'
        """,
    )
    protected abstract suspend fun claimInternal(documentId: Long, claimedAtMillis: Long): Int

    @Transaction
    open suspend fun claimNextQueued(sourceId: Long, claimedAtMillis: Long): DocumentEntity? {
        val candidate = nextQueuedInternal(sourceId) ?: return null
        return if (claimInternal(candidate.id, claimedAtMillis) == 1) {
            candidate.copy(
                analysisStatus = com.sagesearch.android.model.AnalysisStatus.ANALYZING.name,
                analyzedAtMillis = claimedAtMillis,
            )
        } else {
            null
        }
    }

    @Query(
        """
        UPDATE documents
        SET analysisStatus = 'QUEUED'
        WHERE id = :documentId AND analysisStatus = 'ANALYZING'
        """,
    )
    abstract suspend fun releaseClaim(documentId: Long): Int

    @Query(
        """
        UPDATE documents
        SET analysisStatus = 'QUEUED'
        WHERE analysisStatus = 'ANALYZING' AND analyzedAtMillis < :cutoffMillis
        """,
    )
    abstract suspend fun recoverAbandoned(cutoffMillis: Long): Int

    @Query(
        """
        UPDATE documents
        SET analysisStatus = :status,
            analyzedAtMillis = :completedAtMillis,
            extractionVersion = :extractionVersion
        WHERE id = :documentId AND analysisStatus = 'ANALYZING'
        """,
    )
    abstract suspend fun completeCheckpoint(
        documentId: Long,
        status: String,
        completedAtMillis: Long,
        extractionVersion: Int,
    ): Int

    @Transaction
    open suspend fun completeWithFts(document: DocumentEntity, searchableText: String): Boolean {
        val stored = findByIdInternal(document.id) ?: return false
        if (stored.analysisStatus != com.sagesearch.android.model.AnalysisStatus.ANALYZING.name) return false
        updateDocumentInternal(document)
        replaceFtsInternal(DocumentFtsEntity(rowId = document.id, searchableText = searchableText))
        return true
    }

    @Query(
        """
        SELECT * FROM documents
        WHERE :query = ''
           OR displayName LIKE '%' || :query || '%' COLLATE NOCASE
           OR ocrText LIKE '%' || :query || '%' COLLATE NOCASE
           OR merchant LIKE '%' || :query || '%' COLLATE NOCASE
           OR transactionDateText LIKE '%' || :query || '%' COLLATE NOCASE
           OR amountText LIKE '%' || :query || '%' COLLATE NOCASE
           OR currencyCode LIKE '%' || :query || '%' COLLATE NOCASE
        ORDER BY analyzedAtMillis DESC
        LIMIT 40
        """,
    )
    abstract suspend fun legacySearch(query: String): List<DocumentEntity>

    @Transaction
    open suspend fun upsertWithFts(document: DocumentEntity, searchableText: String): Long {
        val existingId = findIdByUriInternal(document.contentUri)
        val stored = document.copy(id = existingId ?: document.id)
        val documentId = if (stored.id == 0L) {
            insertDocumentInternal(stored)
        } else {
            updateDocumentInternal(stored)
            stored.id
        }
        replaceFtsInternal(DocumentFtsEntity(rowId = documentId, searchableText = searchableText))
        return documentId
    }

    @Transaction
    open suspend fun deleteWithFts(documentId: Long) {
        deleteFtsInternal(documentId)
        deleteDocumentInternal(documentId)
    }
}

@Dao
interface DocumentSearchDao {
    @Query(
        """
        SELECT documents.* FROM documents
        JOIN documents_fts ON documents.id = documents_fts.rowid
        WHERE documents_fts MATCH :matchQuery
        ORDER BY documents.analyzedAtMillis DESC, documents.id DESC
        LIMIT :limit
        """,
    )
    suspend fun searchFts(matchQuery: String, limit: Int): List<DocumentEntity>

    @Query(
        """
        SELECT * FROM documents
        WHERE transactionDateIso IS NOT NULL
          AND transactionDateIso >= :fromIso
          AND transactionDateIso <= :toIso
        ORDER BY analyzedAtMillis DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun searchDateRange(fromIso: String, toIso: String, limit: Int): List<DocumentEntity>

    @Query(
        """
        SELECT * FROM documents
        WHERE amountMinor IS NOT NULL
          AND amountMinor >= :minimumMinor
          AND amountMinor <= :maximumMinor
          AND (:currencyCode IS NULL OR currencyCode = :currencyCode COLLATE NOCASE)
        ORDER BY analyzedAtMillis DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun searchAmountRange(
        minimumMinor: Long,
        maximumMinor: Long,
        currencyCode: String?,
        limit: Int,
    ): List<DocumentEntity>

    @Query(
        """
        SELECT * FROM documents
        WHERE currencyCode = :currencyCode COLLATE NOCASE
        ORDER BY analyzedAtMillis DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun searchCurrency(currencyCode: String, limit: Int): List<DocumentEntity>

    @Query(
        """
        SELECT * FROM documents
        WHERE merchant IS NOT NULL AND instr(lower(merchant), lower(:merchant)) > 0
        ORDER BY analyzedAtMillis DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun searchMerchant(merchant: String, limit: Int): List<DocumentEntity>

    @Query(
        """
        SELECT * FROM documents
        WHERE contentKind IN ('receipt', 'mixed') OR receiptConfidence >= 0.20
        ORDER BY receiptConfidence DESC, analyzedAtMillis DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun searchReceiptCandidates(limit: Int): List<DocumentEntity>
}
