package com.sagesearch.android.index

import com.sagesearch.android.ImageAnalysisInterpreter
import com.sagesearch.android.data.db.DocumentEntity
import com.sagesearch.android.model.AnalysisStatus

class ExtractionPipeline(
    private val images: ImageTextExtractor,
    private val pdfs: PdfTextExtractor,
) : IndexCheckpointProcessor {
    override suspend fun process(document: DocumentEntity): DocumentCheckpoint = when {
        document.mimeType.lowercase() in SUPPORTED_IMAGE_TYPES -> {
            checkpoint(
                text = images.extract(document.contentUri),
                status = AnalysisStatus.INDEXED,
            )
        }

        document.mimeType.equals(PDF_MIME_TYPE, ignoreCase = true) -> {
            val extracted = pdfs.extract(document.contentUri)
            checkpoint(
                text = extracted.text,
                status = if (extracted.pageCount > extracted.processedPages) {
                    AnalysisStatus.PARTIALLY_INDEXED
                } else {
                    AnalysisStatus.INDEXED
                },
            )
        }

        else -> DocumentCheckpoint(status = AnalysisStatus.UNSUPPORTED_CONTENT)
    }

    private fun checkpoint(text: String, status: AnalysisStatus): DocumentCheckpoint {
        val analysis = ImageAnalysisInterpreter.interpret(text)
        return DocumentCheckpoint(
            status = status,
            ocrText = analysis.ocrText,
            contentKind = analysis.contentKind,
            receiptConfidence = analysis.receiptConfidence,
            merchant = analysis.receipt.merchantCandidate,
            transactionDateIso = analysis.receipt.transactionDateIso,
            transactionDateText = analysis.receipt.transactionDateText,
            amountMinor = normalizeAmountMinor(analysis.receipt.total, analysis.receipt.currency),
            amountText = analysis.receipt.totalText,
            currencyCode = analysis.receipt.currency,
            extractionVersion = EXTRACTION_VERSION,
        )
    }

    companion object {
        const val EXTRACTION_VERSION = 1
        private const val PDF_MIME_TYPE = "application/pdf"
        private val SUPPORTED_IMAGE_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/heic",
            "image/heif",
        )

        internal fun normalizeAmountMinor(total: Double?, currency: String?): Long? = total?.let { value ->
            if (currency.equals("IDR", ignoreCase = true)) value.toLong() else (value * 100.0).toLong()
        }
    }
}
