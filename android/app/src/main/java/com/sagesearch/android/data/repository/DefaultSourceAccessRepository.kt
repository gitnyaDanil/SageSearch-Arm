package com.sagesearch.android.data.repository

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.sagesearch.android.data.db.ApprovedSourceEntity
import com.sagesearch.android.data.db.DocumentEntity
import com.sagesearch.android.data.db.SageSearchDatabase
import com.sagesearch.android.data.storage.DiscoveredDocument
import com.sagesearch.android.data.storage.DocumentMetadataReader
import com.sagesearch.android.data.storage.DocumentTreeSource
import com.sagesearch.android.model.AnalysisStatus
import com.sagesearch.android.model.SourceKind
import com.sagesearch.android.model.SourceStatus
import com.sagesearch.android.index.IndexScheduler
import com.sagesearch.android.index.IndexSourceGateway
import com.sagesearch.android.index.NoOpIndexScheduler
import com.sagesearch.android.index.SourceScanResult
import com.sagesearch.android.index.ExtractionPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

class DefaultSourceAccessRepository(
    private val resolver: ContentResolver,
    private val database: SageSearchDatabase,
    private val indexScheduler: IndexScheduler = NoOpIndexScheduler,
) : SourceAccessRepository, IndexSourceGateway {
    private val metadata = DocumentMetadataReader(resolver)
    private val trees = DocumentTreeSource(resolver)

    override suspend fun snapshot() = withContext(Dispatchers.IO) { snapshotInternal() }

    override fun observeSnapshots(): Flow<SourceAccessSnapshot> = combine(
        database.approvedSourceDao().observeList(),
        database.documentDao().observeCounts(),
    ) { sources, counts ->
        SourceAccessSnapshot(
            approvedSourceCount = sources.size,
            searchableDocumentCount = counts.searchableDocumentCount,
            needsAccessCount = sources.count { it.status == SourceStatus.NEEDS_ACCESS.name },
            completedDocumentCount = counts.completedDocumentCount,
            pendingDocumentCount = counts.pendingDocumentCount,
        )
    }

    override suspend fun approveTree(uri: String) = withContext(Dispatchers.IO) {
        val treeUri = Uri.parse(uri)
        persistReadGrant(treeUri)
        val label = treeLabel(treeUri)
        val sourceId = database.approvedSourceDao().upsert(source(uri, label, SourceKind.TREE, SourceStatus.INDEXING))
        val result = refreshSourceForIndex(sourceId)
        if (!result.accessible) throw SecurityException("Approved source is not readable")
        indexScheduler.enqueueRefresh(sourceId)
        SourceApprovalResult(1, result.discoveredDocuments)
    }

    override suspend fun approveDocuments(uris: List<String>) = withContext(Dispatchers.IO) {
        var count = 0
        uris.distinct().forEach { rawUri ->
            val uri = Uri.parse(rawUri)
            persistReadGrant(uri)
            val document = metadata.read(uri)
            val sourceId = database.approvedSourceDao().upsert(
                source(rawUri, document.displayName, SourceKind.INDIVIDUAL_FILE, SourceStatus.INDEXING).copy(
                    discoveredCount = 0,
                    indexedCount = 0,
                    lastScannedAtMillis = System.currentTimeMillis(),
                ),
            )
            upsertMetadata(sourceId, document, document.displayName)
            updateSourceAfterDiscovery(sourceId, 1)
            indexScheduler.enqueueRefresh(sourceId)
            count += 1
        }
        SourceApprovalResult(count, count)
    }

    override suspend fun refreshAll() = withContext(Dispatchers.IO) {
        database.approvedSourceDao().list().forEach { source ->
            val result = refreshSourceForIndex(source.id)
            if (result.accessible) indexScheduler.enqueueRefresh(source.id)
        }
        snapshotInternal()
    }

    override suspend fun refreshSourceForIndex(sourceId: Long): SourceScanResult = withContext(Dispatchers.IO) {
        val source = database.approvedSourceDao().findById(sourceId)
            ?: return@withContext SourceScanResult(accessible = false, discoveredDocuments = 0)
        if (!hasPersistedReadGrant(source.uri)) {
            markNeedsAccess(sourceId)
            return@withContext SourceScanResult(accessible = false, discoveredDocuments = 0)
        }
        runCatching { refreshSourceRecord(source) }
            .getOrElse {
                markNeedsAccess(sourceId)
                SourceScanResult(accessible = false, discoveredDocuments = 0)
            }
    }

    override suspend fun markNeedsAccess(sourceId: Long) {
        database.approvedSourceDao().updateStatus(sourceId, SourceStatus.NEEDS_ACCESS.name)
    }

    private suspend fun refreshSourceRecord(source: ApprovedSourceEntity): SourceScanResult {
        when (SourceKind.valueOf(source.kind)) {
            SourceKind.TREE -> {
                val treeUri = Uri.parse(source.uri)
                val label = treeLabel(treeUri)
                val documents = trees.enumerate(treeUri)
                documents.forEach { upsertMetadata(source.id, it, label) }
                database.approvedSourceDao().upsert(source.copy(label = label))
                updateSourceAfterDiscovery(source.id, documents.size)
                return SourceScanResult(accessible = true, discoveredDocuments = documents.size)
            }

            SourceKind.INDIVIDUAL_FILE -> {
                val document = metadata.read(Uri.parse(source.uri))
                upsertMetadata(source.id, document, document.displayName)
                database.approvedSourceDao().upsert(source.copy(label = document.displayName))
                updateSourceAfterDiscovery(source.id, 1)
                return SourceScanResult(accessible = true, discoveredDocuments = 1)
            }
        }
    }

    private suspend fun snapshotInternal(): SourceAccessSnapshot {
        val sources = database.approvedSourceDao().list()
        return SourceAccessSnapshot(
            approvedSourceCount = sources.size,
            searchableDocumentCount = database.documentDao().count(),
            needsAccessCount = sources.count { it.status == SourceStatus.NEEDS_ACCESS.name },
            completedDocumentCount = sources.sumOf { database.documentDao().completedCountForSource(it.id) },
            pendingDocumentCount = sources.sumOf { database.documentDao().pendingCountForSource(it.id) },
        )
    }

    private suspend fun upsertMetadata(sourceId: Long, document: DiscoveredDocument, label: String) {
        val existing = database.documentDao().findByUri(document.uri.toString())
        val changed = existing == null ||
            existing.displayName != document.displayName ||
            existing.mimeType != document.mimeType ||
            existing.sizeBytes != document.sizeBytes ||
            existing.modifiedAtMillis != document.modifiedAtMillis
        val shouldQueue = changed || existing.analysisStatus in setOf(
            AnalysisStatus.DISCOVERED.name,
            AnalysisStatus.NEEDS_ACCESS.name,
        ) || existing.extractionVersion < ExtractionPipeline.EXTRACTION_VERSION
        val discovered = DocumentEntity(
            id = existing?.id ?: 0,
            sourceId = sourceId,
            contentUri = document.uri.toString(),
            displayName = document.displayName,
            mimeType = document.mimeType,
            sizeBytes = document.sizeBytes,
            modifiedAtMillis = document.modifiedAtMillis,
            analyzedAtMillis = 0,
            analysisStatus = AnalysisStatus.QUEUED.name,
            receiptConfidence = 0.0,
            ocrText = "",
            contentKind = "metadata",
            merchant = null,
            transactionDateIso = null,
            transactionDateText = null,
            amountMinor = null,
            amountText = null,
            currencyCode = null,
            extractionVersion = 0,
        )
        val entity = if (shouldQueue) {
            discovered
        } else {
            requireNotNull(existing).copy(
                sourceId = sourceId,
                displayName = document.displayName,
                mimeType = document.mimeType,
                sizeBytes = document.sizeBytes,
                modifiedAtMillis = document.modifiedAtMillis,
            )
        }
        database.documentDao().upsertWithFts(entity, searchableText(entity, label))
    }

    private suspend fun updateSourceAfterDiscovery(sourceId: Long, discoveredCount: Int) {
        val pending = database.documentDao().pendingCountForSource(sourceId)
        database.approvedSourceDao().updateProgress(
            sourceId = sourceId,
            status = if (pending > 0) SourceStatus.INDEXING.name else SourceStatus.READY.name,
            discoveredCount = discoveredCount,
            indexedCount = database.documentDao().completedCountForSource(sourceId),
            scannedAtMillis = System.currentTimeMillis(),
        )
    }

    private fun searchableText(document: DocumentEntity, label: String): String = listOfNotNull(
        document.displayName,
        label,
        document.ocrText.takeIf(String::isNotBlank),
        document.merchant,
        document.transactionDateIso,
        document.transactionDateText,
        document.amountText,
        document.currencyCode,
    ).joinToString(" ")

    private fun source(uri: String, label: String, kind: SourceKind, status: SourceStatus) = ApprovedSourceEntity(
        uri = uri,
        label = label,
        kind = kind.name,
        status = status.name,
        discoveredCount = 0,
        indexedCount = 0,
        lastScannedAtMillis = null,
    )

    private fun persistReadGrant(uri: Uri) {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun hasPersistedReadGrant(uri: String): Boolean = resolver.persistedUriPermissions.any {
        it.isReadPermission && it.uri.toString() == uri
    }

    private fun treeLabel(treeUri: Uri): String = runCatching {
        val rootDocument = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        metadata.read(rootDocument).displayName
    }.getOrDefault(treeUri.lastPathSegment ?: "Approved folder")
}
