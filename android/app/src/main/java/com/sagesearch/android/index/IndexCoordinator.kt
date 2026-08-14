package com.sagesearch.android.index

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class IndexCoordinator internal constructor(
    private val backend: IndexWorkBackend,
) : IndexScheduler {
    constructor(workManager: WorkManager) : this(WorkManagerIndexBackend(workManager))

    override fun enqueueRefresh(sourceId: Long) {
        backend.enqueue(uniqueName(sourceId), ExistingWorkPolicy.KEEP, sourceId)
    }

    override fun enqueueContinuation(sourceId: Long) {
        backend.enqueue(uniqueName(sourceId), ExistingWorkPolicy.APPEND_OR_REPLACE, sourceId)
    }

    fun observeProgress(sourceId: Long): Flow<IndexProgress> =
        backend.observeProgress(uniqueName(sourceId))

    companion object {
        fun uniqueName(sourceId: Long) = "sagesearch-index-source-$sourceId"
    }
}

internal interface IndexWorkBackend {
    fun enqueue(uniqueName: String, policy: ExistingWorkPolicy, sourceId: Long)
    fun observeProgress(uniqueName: String): Flow<IndexProgress>
}

private class WorkManagerIndexBackend(
    private val workManager: WorkManager,
) : IndexWorkBackend {
    override fun enqueue(uniqueName: String, policy: ExistingWorkPolicy, sourceId: Long) {
        val request = OneTimeWorkRequestBuilder<DocumentIndexWorker>()
            .setInputData(workDataOf(DocumentIndexWorker.INPUT_SOURCE_ID to sourceId))
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniqueWork(uniqueName, policy, request)
    }

    override fun observeProgress(uniqueName: String): Flow<IndexProgress> =
        workManager.getWorkInfosForUniqueWorkFlow(uniqueName).map { work ->
            val latest = work.lastOrNull()
            IndexProgress(
                completedInBatch = latest?.progress?.getInt(DocumentIndexWorker.PROGRESS_COMPLETED, 0) ?: 0,
                remainingDocuments = latest?.progress?.getInt(DocumentIndexWorker.PROGRESS_REMAINING, 0) ?: 0,
            )
        }

    companion object {
        private const val WORK_TAG = "sagesearch-index"
    }
}
