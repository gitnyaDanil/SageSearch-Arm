package com.sagesearch.android.index

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri

data class PdfOcrResult(
    val text: String,
    val pageCount: Int,
    val processedPages: Int,
)

fun interface PdfTextExtractor {
    suspend fun extract(contentUri: String): PdfOcrResult
}

class PdfOcrExtractor(
    private val resolver: ContentResolver,
    private val recognizer: OcrTextRecognizer,
    private val maximumPages: Int = MAXIMUM_PAGES,
    private val maximumLongestEdge: Int = ImageOcrExtractor.MAXIMUM_LONGEST_EDGE,
) : PdfTextExtractor {
    override suspend fun extract(contentUri: String): PdfOcrResult {
        val uri = Uri.parse(contentUri)
        val descriptor = requireNotNull(resolver.openFileDescriptor(uri, "r")) { "PDF is not openable" }
        descriptor.use {
            PdfRenderer(descriptor).use { renderer ->
                val processed = minOf(renderer.pageCount, maximumPages)
                val text = buildString {
                    repeat(processed) { pageIndex ->
                        renderer.openPage(pageIndex).use { page ->
                            val scale = maximumLongestEdge.toFloat() / maxOf(page.width, page.height)
                            val boundedScale = minOf(1f, scale)
                            val width = (page.width * boundedScale).toInt().coerceAtLeast(1)
                            val height = (page.height * boundedScale).toInt().coerceAtLeast(1)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            try {
                                val matrix = Matrix().apply { setScale(boundedScale, boundedScale) }
                                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                if (isNotEmpty()) append("\n--- page ${pageIndex + 1} ---\n")
                                append(recognizer.recognize(bitmap).take(MAXIMUM_CHARS_PER_PAGE))
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
                return PdfOcrResult(text = text, pageCount = renderer.pageCount, processedPages = processed)
            }
        }
    }

    companion object {
        const val MAXIMUM_PAGES = 5
        private const val MAXIMUM_CHARS_PER_PAGE = 50_000
    }
}
