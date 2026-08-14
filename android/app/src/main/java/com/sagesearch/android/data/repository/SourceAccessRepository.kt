package com.sagesearch.android.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class SourceAccessSnapshot(
    val approvedSourceCount: Int,
    val searchableDocumentCount: Int,
    val needsAccessCount: Int,
    val completedDocumentCount: Int = 0,
    val pendingDocumentCount: Int = 0,
)

data class SourceApprovalResult(val approvedSources: Int, val discoveredDocuments: Int)

interface SourceAccessRepository {
    suspend fun snapshot(): SourceAccessSnapshot
    fun observeSnapshots(): Flow<SourceAccessSnapshot> = flow { emit(snapshot()) }
    suspend fun approveTree(uri: String): SourceApprovalResult
    suspend fun approveDocuments(uris: List<String>): SourceApprovalResult
    suspend fun refreshAll(): SourceAccessSnapshot
}
