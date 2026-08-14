package com.sagesearch.android.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.sagesearch.android.data.repository.PrototypeImageRepository
import com.sagesearch.android.data.repository.SourceAccessRepository
import com.sagesearch.android.data.repository.SourceAccessSnapshot
import com.sagesearch.android.modelruntime.ModelRepository
import com.sagesearch.android.modelruntime.ModelState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SetupViewModel(
    private val images: PrototypeImageRepository,
    private val models: ModelRepository,
    private val sourceAccess: SourceAccessRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = mutableState.asStateFlow()
    private var modelImportJob: Job? = null

    init {
        viewModelScope.launch {
            sourceAccess.observeSnapshots().collectLatest(::applyObservedSnapshot)
        }
        viewModelScope.launch {
            models.state.collectLatest(::applyModelState)
        }
        refresh()
    }

    private fun applyObservedSnapshot(snapshot: SourceAccessSnapshot) {
        mutableState.update { current ->
            current.copy(
                isLoading = false,
                sourceStatus = when {
                    snapshot.needsAccessCount > 0 -> SetupTaskStatus.NEEDS_ATTENTION
                    snapshot.searchableDocumentCount > 0 -> SetupTaskStatus.READY
                    else -> SetupTaskStatus.NOT_STARTED
                },
                indexedCount = snapshot.searchableDocumentCount,
                approvedSourceCount = snapshot.approvedSourceCount,
                needsAccessCount = snapshot.needsAccessCount,
                completedIndexCount = snapshot.completedDocumentCount,
                pendingIndexCount = snapshot.pendingDocumentCount,
            )
        }
    }

    private fun applyModelState(modelState: ModelState) {
        mutableState.update { current ->
            when (modelState) {
                ModelState.NotPrepared -> current.copy(
                    modelStatus = SetupTaskStatus.NOT_STARTED,
                    modelDetail = "Basic indexed search works without the model.",
                    modelProgressPercent = null,
                )
                is ModelState.Importing -> {
                    val percent = modelState.totalBytes
                        ?.takeIf { it > 0L }
                        ?.let { ((modelState.copiedBytes * 100L) / it).coerceIn(0L, 100L).toInt() }
                    current.copy(
                        modelStatus = SetupTaskStatus.IN_PROGRESS,
                        modelDetail = percent?.let { "Copying model into private storage: $it%." }
                            ?: "Copying model into private storage...",
                        modelProgressPercent = percent,
                    )
                }
                ModelState.Initializing -> current.copy(
                    modelStatus = SetupTaskStatus.IN_PROGRESS,
                    modelDetail = "Checking model compatibility and initializing LiteRT-LM on CPU...",
                    modelProgressPercent = null,
                )
                is ModelState.Ready -> current.copy(
                    modelStatus = SetupTaskStatus.READY,
                    modelDetail = modelState.notice
                        ?: "On-device refinement is ready (${modelState.metadata.sha256.take(12)}...).",
                    modelProgressPercent = null,
                )
                is ModelState.NeedsAttention -> current.copy(
                    modelStatus = SetupTaskStatus.NEEDS_ATTENTION,
                    modelDetail = modelState.message,
                    modelProgressPercent = null,
                )
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val sourceResult = runCatching { sourceAccess.refreshAll() }
            val snapshot = sourceResult.getOrNull()
            val count = snapshot?.searchableDocumentCount
                ?: runCatching { images.indexedCount() }.getOrDefault(0)
            mutableState.update { current ->
                current.copy(
                    isLoading = false,
                    sourceStatus = when {
                        sourceResult.isFailure || (snapshot?.needsAccessCount ?: 0) > 0 -> SetupTaskStatus.NEEDS_ATTENTION
                        count > 0 -> SetupTaskStatus.READY
                        else -> SetupTaskStatus.NOT_STARTED
                    },
                    indexedCount = count,
                    approvedSourceCount = snapshot?.approvedSourceCount ?: 0,
                    needsAccessCount = snapshot?.needsAccessCount ?: 0,
                    completedIndexCount = snapshot?.completedDocumentCount ?: 0,
                    pendingIndexCount = snapshot?.pendingDocumentCount ?: 0,
                    message = sourceResult.exceptionOrNull()?.let {
                        "SageSearch can only search files you approve."
                    },
                )
            }
        }
    }

    fun importModel(uri: String) {
        if (modelImportJob?.isActive == true) return
        modelImportJob = viewModelScope.launch { models.importModel(uri) }
    }

    fun cancelModelImport() {
        modelImportJob?.cancel()
    }

    fun approveTree(uri: String) = approveSources { sourceAccess.approveTree(uri) }

    fun approveDocuments(uris: List<String>) {
        if (uris.isNotEmpty()) approveSources { sourceAccess.approveDocuments(uris) }
    }

    private fun approveSources(approval: suspend () -> Unit) {
        mutableState.update { it.copy(sourceStatus = SetupTaskStatus.IN_PROGRESS, message = null) }
        viewModelScope.launch {
            runCatching { approval() }
                .onSuccess { refresh() }
                .onFailure {
                    mutableState.update { current ->
                        current.copy(
                            sourceStatus = SetupTaskStatus.NEEDS_ATTENTION,
                            message = "SageSearch can only search files you approve.",
                        )
                    }
                }
        }
    }

    class Factory(
        private val images: PrototypeImageRepository,
        private val models: ModelRepository,
        private val sourceAccess: SourceAccessRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SetupViewModel(images, models, sourceAccess) as T
    }
}
