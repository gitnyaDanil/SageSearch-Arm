package com.sagesearch.android.ui.setup

import com.sagesearch.android.ImageAnalysisResult

enum class SetupTaskStatus { NOT_STARTED, IN_PROGRESS, READY, NEEDS_ATTENTION }

data class SetupUiState(
    val isLoading: Boolean = true,
    val sourceStatus: SetupTaskStatus = SetupTaskStatus.NOT_STARTED,
    val modelStatus: SetupTaskStatus = SetupTaskStatus.NOT_STARTED,
    val indexedCount: Int = 0,
    val approvedSourceCount: Int = 0,
    val needsAccessCount: Int = 0,
    val completedIndexCount: Int = 0,
    val pendingIndexCount: Int = 0,
    val modelDetail: String = "Basic indexed search works without the model.",
    val modelProgressPercent: Int? = null,
    val latestAnalysis: ImageAnalysisResult? = null,
    val message: String? = null,
) {
    val canSearch: Boolean get() = indexedCount > 0
}
