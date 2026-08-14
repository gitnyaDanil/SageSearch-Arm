package com.sagesearch.android

object ImageAnalysisInterpreter {
    internal const val RECEIPT_THRESHOLD = 0.45
    internal const val MIXED_THRESHOLD = 0.20

    fun interpret(rawOcrText: String): ImageAnalysisResult {
        val ocrText = rawOcrText.trim()
        val (receiptConfidence, candidateFields) = ReceiptHeuristics.analyze(ocrText)
        val contentKind = when {
            ocrText.isBlank() -> "unknown"
            receiptConfidence >= RECEIPT_THRESHOLD -> "receipt"
            receiptConfidence >= MIXED_THRESHOLD -> "mixed"
            else -> "picture"
        }

        return ImageAnalysisResult(
            contentKind = contentKind,
            receiptConfidence = receiptConfidence,
            ocrText = ocrText,
            receipt = if (contentKind == "receipt" || contentKind == "mixed") {
                candidateFields
            } else {
                ReceiptFields()
            },
        )
    }
}
