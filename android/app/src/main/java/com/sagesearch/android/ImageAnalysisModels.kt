package com.sagesearch.android

data class ReceiptFields(
    val merchantCandidate: String? = null,
    val transactionDateIso: String? = null,
    val transactionDateText: String? = null,
    val totalText: String? = null,
    val total: Double? = null,
    val currency: String? = null,
)

data class ImageAnalysisResult(
    val contentKind: String,
    val receiptConfidence: Double,
    val ocrText: String,
    val receipt: ReceiptFields,
)

sealed interface AnalysisUiState {
    data object Idle : AnalysisUiState
    data object Analyzing : AnalysisUiState
    data class Success(val result: ImageAnalysisResult) : AnalysisUiState
    data class Error(val message: String) : AnalysisUiState
}
