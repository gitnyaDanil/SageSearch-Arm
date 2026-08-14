package com.sagesearch.android.index

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sagesearch.android.SageSearchApplication

class DocumentIndexWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val sourceId = inputData.getLong(INPUT_SOURCE_ID, INVALID_SOURCE_ID)
        if (sourceId == INVALID_SOURCE_ID) return Result.failure()
        val container = (applicationContext as? SageSearchApplication)?.container ?: return Result.failure()
        val outcome = container.documentIndexWorkerRunner.runBatch(
            sourceId = sourceId,
            isStopped = { isStopped },
            onProgress = { progress ->
                setProgress(
                    workDataOf(
                        PROGRESS_COMPLETED to progress.completedInBatch,
                        PROGRESS_REMAINING to progress.remainingDocuments,
                    ),
                )
            },
        )
        return when (outcome) {
            is IndexBatchOutcome.Complete,
            IndexBatchOutcome.AccessRevoked,
            -> Result.success()

            is IndexBatchOutcome.MoreWork -> {
                container.indexCoordinator.enqueueContinuation(sourceId)
                Result.success()
            }

            is IndexBatchOutcome.Retry -> {
                if (runAttemptCount < MAX_TRANSIENT_ATTEMPTS - 1) {
                    Result.retry()
                } else {
                    container.documentIndexWorkerRunner.failOnePendingAfterRetries(sourceId)
                    container.indexCoordinator.enqueueContinuation(sourceId)
                    Result.success()
                }
            }

            IndexBatchOutcome.Stopped -> Result.retry()
        }
    }

    companion object {
        const val INPUT_SOURCE_ID = "source_id"
        const val PROGRESS_COMPLETED = "completed"
        const val PROGRESS_REMAINING = "remaining"
        private const val INVALID_SOURCE_ID = -1L
        private const val MAX_TRANSIENT_ATTEMPTS = 3
    }
}
