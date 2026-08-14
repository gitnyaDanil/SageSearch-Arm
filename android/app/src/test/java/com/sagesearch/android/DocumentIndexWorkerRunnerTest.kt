package com.sagesearch.android

import com.sagesearch.android.data.db.DocumentEntity
import com.sagesearch.android.index.DocumentCheckpoint
import com.sagesearch.android.index.DocumentIndexWorkerRunner
import com.sagesearch.android.index.HeavyWorkCoordinator
import com.sagesearch.android.index.IndexBatchOutcome
import com.sagesearch.android.index.IndexCheckpointProcessor
import com.sagesearch.android.index.IndexDocumentRepository
import com.sagesearch.android.index.IndexSourceGateway
import com.sagesearch.android.index.RetryableIndexException
import com.sagesearch.android.index.SourceScanResult
import com.sagesearch.android.model.AnalysisStatus
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentIndexWorkerRunnerTest {
    @Test
    fun batchCheckpointsTenDocumentsAndReportsRemainingWork() = runTest {
        val documents = FakeDocuments(12)
        val progress = mutableListOf<Int>()
        val runner = runner(documents = documents)

        val outcome = runner.runBatch(7, onProgress = { progress += it.remainingDocuments })

        assertTrue(outcome is IndexBatchOutcome.MoreWork)
        assertEquals(10, documents.completed.size)
        assertEquals(2, documents.remaining(7))
        assertEquals(2, progress.last())
        assertTrue(documents.sourceProgressUpdates > 0)
    }

    @Test
    fun batchYieldsWhenItsInternalTimeBudgetExpires() = runTest {
        val documents = FakeDocuments(5)
        var clock = 0L
        val runner = DocumentIndexWorkerRunner(
            sources = FakeSources(),
            documents = documents,
            processor = IndexCheckpointProcessor {
                clock += 6
                DocumentCheckpoint(AnalysisStatus.METADATA_INDEXED)
            },
            heavyWork = HeavyWorkCoordinator(),
            nowMillis = { clock },
            timeBudgetMillis = 5,
        )

        val outcome = runner.runBatch(7)

        assertTrue(outcome is IndexBatchOutcome.MoreWork)
        assertEquals(1, documents.completed.size)
        assertEquals(4, documents.remaining(7))
    }

    @Test
    fun retryableFailureReleasesClaimForAWorkManagerRetry() = runTest {
        val documents = FakeDocuments(1)
        val runner = runner(
            documents = documents,
            processor = IndexCheckpointProcessor { throw RetryableIndexException() },
        )

        val outcome = runner.runBatch(7)

        assertTrue(outcome is IndexBatchOutcome.Retry)
        assertEquals(AnalysisStatus.QUEUED, documents.status(1))
        assertEquals(1, documents.remaining(7))
    }

    @Test
    fun cancellationReleasesTheActiveClaim() = runTest {
        val documents = FakeDocuments(1)
        val runner = runner(
            documents = documents,
            processor = IndexCheckpointProcessor { throw CancellationException() },
        )

        var canceled = false
        try {
            runner.runBatch(7)
        } catch (expected: CancellationException) {
            canceled = true
        }

        assertTrue(canceled)
        assertEquals(AnalysisStatus.QUEUED, documents.status(1))
    }

    @Test
    fun revokedSourceStopsBeforeAnyDocumentClaim() = runTest {
        val documents = FakeDocuments(1)
        val runner = runner(documents = documents, sources = FakeSources(accessible = false))

        val outcome = runner.runBatch(7)

        assertEquals(IndexBatchOutcome.AccessRevoked, outcome)
        assertEquals(0, documents.claims)
    }

    @Test
    fun stoppedWorkerLeavesPendingDocumentsUntouched() = runTest {
        val documents = FakeDocuments(2)
        val outcome = runner(documents = documents).runBatch(7, isStopped = { true })

        assertEquals(IndexBatchOutcome.Stopped, outcome)
        assertEquals(0, documents.claims)
        assertEquals(2, documents.remaining(7))
    }

    @Test
    fun stableProcessorFailureIsPersistedWithoutContent() = runTest {
        val documents = FakeDocuments(1)
        val runner = runner(
            documents = documents,
            processor = IndexCheckpointProcessor { error("private detail must not be logged") },
        )

        val outcome = runner.runBatch(7)

        assertTrue(outcome is IndexBatchOutcome.Complete)
        assertEquals(AnalysisStatus.FAILED, documents.status(1))
    }

    @Test
    fun startupRecoveryUsesAnAbandonmentCutoff() = runTest {
        val documents = FakeDocuments(0)
        documents.recovered = 2
        var clock = 1_000_000L
        val runner = runner(documents = documents, now = { clock })

        assertEquals(2, runner.recoverAbandoned(maximumClaimAgeMillis = 60_000))
        assertEquals(940_000L, documents.recoveryCutoff)
    }

    private fun runner(
        documents: FakeDocuments,
        sources: FakeSources = FakeSources(),
        processor: IndexCheckpointProcessor = IndexCheckpointProcessor {
            DocumentCheckpoint(AnalysisStatus.METADATA_INDEXED)
        },
        now: () -> Long = { 100L },
    ) = DocumentIndexWorkerRunner(
        sources = sources,
        documents = documents,
        processor = processor,
        heavyWork = HeavyWorkCoordinator(),
        nowMillis = now,
    )
}

private class FakeSources(
    private val accessible: Boolean = true,
) : IndexSourceGateway {
    override suspend fun refreshSourceForIndex(sourceId: Long) = SourceScanResult(accessible, 1)
    override suspend fun markNeedsAccess(sourceId: Long) = Unit
}

private class FakeDocuments(count: Int) : IndexDocumentRepository {
    private val statuses = (1..count).associate { it.toLong() to AnalysisStatus.QUEUED }.toMutableMap()
    val completed = mutableListOf<Long>()
    var claims = 0
    var sourceProgressUpdates = 0
    var recovered = 0
    var recoveryCutoff: Long? = null

    override suspend fun claimNext(sourceId: Long, claimedAtMillis: Long): DocumentEntity? {
        val id = statuses.entries.firstOrNull { it.value == AnalysisStatus.QUEUED }?.key ?: return null
        statuses[id] = AnalysisStatus.ANALYZING
        claims += 1
        return document(id)
    }

    override suspend fun complete(
        document: DocumentEntity,
        checkpoint: DocumentCheckpoint,
        completedAtMillis: Long,
    ): Boolean {
        val documentId = document.id
        if (statuses[documentId] != AnalysisStatus.ANALYZING) return false
        statuses[documentId] = checkpoint.status
        completed += documentId
        return true
    }

    override suspend fun releaseClaim(documentId: Long): Boolean {
        if (statuses[documentId] != AnalysisStatus.ANALYZING) return false
        statuses[documentId] = AnalysisStatus.QUEUED
        return true
    }

    override suspend fun recoverAbandoned(cutoffMillis: Long): Int {
        recoveryCutoff = cutoffMillis
        return recovered
    }

    override suspend fun remaining(sourceId: Long) = statuses.values.count {
        it == AnalysisStatus.QUEUED || it == AnalysisStatus.ANALYZING
    }

    override suspend fun updateSourceProgress(sourceId: Long, scannedAtMillis: Long) {
        sourceProgressUpdates += 1
    }

    fun status(documentId: Long) = statuses.getValue(documentId)

    private fun document(id: Long) = DocumentEntity(
        id = id,
        sourceId = 7,
        contentUri = "content://test/$id",
        displayName = "document-$id",
        mimeType = "application/octet-stream",
        sizeBytes = id,
        modifiedAtMillis = id,
        analyzedAtMillis = 0,
        analysisStatus = AnalysisStatus.ANALYZING.name,
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
}
