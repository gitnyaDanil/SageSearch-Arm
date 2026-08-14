package com.sagesearch.android

import com.sagesearch.android.data.db.DocumentEntity
import com.sagesearch.android.index.ExtractionPipeline
import com.sagesearch.android.index.ImageOcrExtractor
import com.sagesearch.android.index.ImageTextExtractor
import com.sagesearch.android.index.PdfOcrResult
import com.sagesearch.android.index.PdfTextExtractor
import com.sagesearch.android.model.AnalysisStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractionPipelineTest {
    @Test
    fun imageOcrBecomesFactualNormalizedReceiptCheckpoint() = runTest {
        val pipeline = ExtractionPipeline(
            images = ImageTextExtractor {
                """
                PUSAT FITNESS NUSANTARA
                Keanggotaan gym
                12 Maret 2026
                TOTAL Rp 200.000
                """.trimIndent()
            },
            pdfs = PdfTextExtractor { error("PDF should not run") },
        )

        val checkpoint = pipeline.process(document("image/jpeg"))

        assertEquals(AnalysisStatus.INDEXED, checkpoint.status)
        assertEquals("PUSAT FITNESS NUSANTARA", checkpoint.merchant)
        assertEquals("2026-03-12", checkpoint.transactionDateIso)
        assertEquals(200_000L, checkpoint.amountMinor)
        assertEquals("IDR", checkpoint.currencyCode)
    }

    @Test
    fun sixthPdfPageProducesAnHonestPartialCheckpoint() = runTest {
        val pipeline = ExtractionPipeline(
            images = ImageTextExtractor { error("Image should not run") },
            pdfs = PdfTextExtractor { PdfOcrResult("first five pages", pageCount = 6, processedPages = 5) },
        )

        val checkpoint = pipeline.process(document("application/pdf"))

        assertEquals(AnalysisStatus.PARTIALLY_INDEXED, checkpoint.status)
        assertEquals("first five pages", checkpoint.ocrText)
    }

    @Test
    fun onePagePdfIsFullyIndexed() = runTest {
        val pipeline = ExtractionPipeline(
            images = ImageTextExtractor { error("Image should not run") },
            pdfs = PdfTextExtractor { PdfOcrResult("receipt", pageCount = 1, processedPages = 1) },
        )

        assertEquals(AnalysisStatus.INDEXED, pipeline.process(document("application/pdf")).status)
    }

    @Test
    fun unsupportedCommonFileRemainsMetadataSearchable() = runTest {
        var extractorCalled = false
        val pipeline = ExtractionPipeline(
            images = ImageTextExtractor { extractorCalled = true; "" },
            pdfs = PdfTextExtractor { extractorCalled = true; PdfOcrResult("", 0, 0) },
        )

        val checkpoint = pipeline.process(document("text/plain"))

        assertEquals(AnalysisStatus.UNSUPPORTED_CONTENT, checkpoint.status)
        assertEquals("", checkpoint.ocrText)
        assertTrue(!extractorCalled)
    }

    @Test(expected = IllegalStateException::class)
    fun corruptPdfFailureEscapesForWorkerFailureCheckpoint() = runTest {
        ExtractionPipeline(
            images = ImageTextExtractor { "" },
            pdfs = PdfTextExtractor { error("corrupt") },
        ).process(document("application/pdf"))
    }

    @Test
    fun imageSamplingNeverExceedsTheConfiguredLongestEdge() {
        assertEquals(1, ImageOcrExtractor.calculateInSampleSize(2_048, 1_024, 2_048))
        assertEquals(4, ImageOcrExtractor.calculateInSampleSize(5_000, 1_000, 2_048))
    }

    private fun document(mimeType: String) = DocumentEntity(
        id = 1,
        sourceId = 1,
        contentUri = "content://test/document",
        displayName = "opaque-file",
        mimeType = mimeType,
        sizeBytes = 100,
        modifiedAtMillis = 1,
        analyzedAtMillis = 1,
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
