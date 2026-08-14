package com.sagesearch.android.index

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sagesearch.android.data.db.DocumentEntity
import com.sagesearch.android.model.AnalysisStatus
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExtractionPipelineDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun largeExifRotatedImageIsBoundedOrientedAndRecycled() = runBlocking {
        val file = File(context.cacheDir, "large-rotated.jpg")
        val source = Bitmap.createBitmap(4_096, 1_024, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.WHITE)
        FileOutputStream(file).use { source.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        source.recycle()
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val recognizer = RecordingRecognizer()

        ImageOcrExtractor(context.contentResolver, recognizer).extract(Uri.fromFile(file).toString())

        val recognized = recognizer.bitmaps.single()
        assertTrue(maxOf(recognized.width, recognized.height) <= ImageOcrExtractor.MAXIMUM_LONGEST_EDGE)
        assertTrue(recognized.height > recognized.width)
        assertTrue(recognized.isRecycled)
        assertTrue(file.delete())
    }

    @Test
    fun sixPagePdfRendersOnlyFivePagesSequentiallyAndRecyclesEveryBitmap() = runBlocking {
        val file = createPdf("six-pages.pdf", pages = 6)
        val recognizer = RecordingRecognizer()

        val result = PdfOcrExtractor(context.contentResolver, recognizer).extract(Uri.fromFile(file).toString())

        assertEquals(6, result.pageCount)
        assertEquals(5, result.processedPages)
        assertEquals(5, recognizer.bitmaps.size)
        assertTrue(recognizer.bitmaps.all(Bitmap::isRecycled))
        assertTrue(result.text.contains("recognized-5"))
        assertFalse(result.text.contains("recognized-6"))
        assertTrue(file.delete())
    }

    @Test
    fun corruptPdfClosesItsDescriptorAndRemainsDeletable() = runBlocking {
        val file = File(context.cacheDir, "corrupt.pdf").apply { writeText("not a pdf") }
        var failed = false
        try {
            PdfOcrExtractor(context.contentResolver, RecordingRecognizer()).extract(Uri.fromFile(file).toString())
        } catch (expected: Exception) {
            failed = true
        }

        assertTrue(failed)
        assertTrue(file.delete())
    }

    @Test
    fun bundledMlKitOcrFeedsDeterministicReceiptNormalization() = runBlocking {
        val file = File(context.cacheDir, "synthetic-gym-receipt.png")
        val bitmap = Bitmap.createBitmap(1_600, 1_000, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 64f
            isAntiAlias = true
        }
        val lines = listOf(
            "PUSAT FITNESS NUSANTARA",
            "KEANGGOTAAN GYM",
            "12 MARET 2026",
            "TOTAL RP 200.000",
        )
        lines.forEachIndexed { index, line -> bitmap.run { CanvasWriter.drawText(this, line, 70f, 160f + index * 170f, paint) } }
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val recognizer = MlKitOcrTextRecognizer()
        try {
            val pipeline = ExtractionPipeline(
                images = ImageOcrExtractor(context.contentResolver, recognizer),
                pdfs = PdfTextExtractor { error("PDF should not run") },
            )

            val checkpoint = pipeline.process(imageDocument(Uri.fromFile(file).toString()))

            assertEquals(AnalysisStatus.INDEXED, checkpoint.status)
            assertTrue(checkpoint.ocrText.contains("FITNESS", ignoreCase = true))
            assertEquals(200_000L, checkpoint.amountMinor)
            assertEquals("2026-03-12", checkpoint.transactionDateIso)
        } finally {
            recognizer.close()
            assertTrue(file.delete())
        }
    }

    private fun createPdf(name: String, pages: Int): File {
        val file = File(context.cacheDir, name)
        val document = PdfDocument()
        try {
            repeat(pages) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, index + 1).create())
                page.canvas.drawText("Page ${index + 1}", 40f, 80f, Paint().apply { textSize = 28f })
                document.finishPage(page)
            }
            FileOutputStream(file).use(document::writeTo)
        } finally {
            document.close()
        }
        return file
    }

    private fun imageDocument(uri: String) = DocumentEntity(
        id = 1,
        sourceId = 1,
        contentUri = uri,
        displayName = "IMG_184522.png",
        mimeType = "image/png",
        sizeBytes = null,
        modifiedAtMillis = null,
        analyzedAtMillis = 0,
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

    private object CanvasWriter {
        fun drawText(bitmap: Bitmap, text: String, x: Float, y: Float, paint: Paint) {
            android.graphics.Canvas(bitmap).drawText(text, x, y, paint)
        }
    }

    private class RecordingRecognizer : OcrTextRecognizer {
        val bitmaps = mutableListOf<Bitmap>()

        override suspend fun recognize(bitmap: Bitmap): String {
            bitmaps += bitmap
            return "recognized-${bitmaps.size}"
        }
    }
}
