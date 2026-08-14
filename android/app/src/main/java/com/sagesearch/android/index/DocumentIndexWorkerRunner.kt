package com.sagesearch.android.index

import com.sagesearch.android.model.AnalysisStatus
import java.util.concurrent.CancellationException

class DocumentIndexWorkerRunner(
    private val sources: IndexSourceGateway,
    private val documents: IndexDocumentRepository,
    private val processor: IndexCheckpointProcessor,
    private val heavyWork: HeavyWorkCoordinator,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val maxDocuments: Int = 10,
    private val timeBudgetMillis: Long = 8 * 60 * 1_000L,
) {
    suspend fun recoverAbandoned(maximumClaimAgeMillis: Long = 10 * 60 * 1_000L): Int =
        documents.recoverAbandoned(nowMillis() - maximumClaimAgeMillis)

    suspend fun runBatch(
        sourceId: Long,
        isStopped: () -> Boolean = { false },
        onProgress: suspend (IndexProgress) -> Unit = {},
    ): IndexBatchOutcome {
        val scan = sources.refreshSourceForIndex(sourceId)
        if (!scan.accessible) return IndexBatchOutcome.AccessRevoked

        val startedAt = nowMillis()
        var completed = 0
        while (completed < maxDocuments && nowMillis() - startedAt < timeBudgetMillis) {
            if (isStopped()) return IndexBatchOutcome.Stopped
            if (heavyWork.isInteractiveWaiting) return moreWork(sourceId, completed, onProgress)
            val document = documents.claimNext(sourceId, nowMillis()) ?: break
            try {
                val checkpoint = heavyWork.withBackgroundWork { processor.process(document) }
                if (!documents.complete(document, checkpoint, nowMillis())) {
                    documents.releaseClaim(document.id)
                    return retry(sourceId, completed, onProgress)
                }
                completed += 1
                val progress = IndexProgress(completed, documents.remaining(sourceId))
                onProgress(progress)
                if (heavyWork.isInteractiveWaiting) {
                    documents.updateSourceProgress(sourceId, nowMillis())
                    return IndexBatchOutcome.MoreWork(progress)
                }
            } catch (error: CancellationException) {
                documents.releaseClaim(document.id)
                throw error
            } catch (error: SecurityException) {
                documents.complete(
                    document,
                    DocumentCheckpoint(status = AnalysisStatus.NEEDS_ACCESS),
                    nowMillis(),
                )
                sources.markNeedsAccess(sourceId)
                return IndexBatchOutcome.AccessRevoked
            } catch (error: RetryableIndexException) {
                documents.releaseClaim(document.id)
                return retry(sourceId, completed, onProgress)
            } catch (error: Exception) {
                documents.complete(
                    document,
                    DocumentCheckpoint(status = AnalysisStatus.FAILED),
                    nowMillis(),
                )
                completed += 1
            }
        }

        documents.updateSourceProgress(sourceId, nowMillis())
        val progress = IndexProgress(completed, documents.remaining(sourceId))
        onProgress(progress)
        return if (progress.remainingDocuments > 0) {
            IndexBatchOutcome.MoreWork(progress)
        } else {
            IndexBatchOutcome.Complete(progress)
        }
    }

    suspend fun failOnePendingAfterRetries(sourceId: Long): Boolean {
        val document = documents.claimNext(sourceId, nowMillis()) ?: return false
        val completed = documents.complete(
            document,
            DocumentCheckpoint(status = AnalysisStatus.FAILED),
            nowMillis(),
        )
        documents.updateSourceProgress(sourceId, nowMillis())
        return completed
    }

    private suspend fun retry(
        sourceId: Long,
        completed: Int,
        onProgress: suspend (IndexProgress) -> Unit,
    ): IndexBatchOutcome.Retry {
        documents.updateSourceProgress(sourceId, nowMillis())
        val progress = IndexProgress(completed, documents.remaining(sourceId))
        onProgress(progress)
        return IndexBatchOutcome.Retry(progress)
    }

    private suspend fun moreWork(
        sourceId: Long,
        completed: Int,
        onProgress: suspend (IndexProgress) -> Unit,
    ): IndexBatchOutcome.MoreWork {
        documents.updateSourceProgress(sourceId, nowMillis())
        val progress = IndexProgress(completed, documents.remaining(sourceId))
        onProgress(progress)
        return IndexBatchOutcome.MoreWork(progress)
    }
}
