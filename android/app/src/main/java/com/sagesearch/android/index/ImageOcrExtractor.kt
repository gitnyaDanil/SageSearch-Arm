package com.sagesearch.android.index

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri

fun interface ImageTextExtractor {
    suspend fun extract(contentUri: String): String
}

class ImageOcrExtractor(
    private val resolver: ContentResolver,
    private val recognizer: OcrTextRecognizer,
    private val maximumLongestEdge: Int = MAXIMUM_LONGEST_EDGE,
) : ImageTextExtractor {
    override suspend fun extract(contentUri: String): String {
        val uri = Uri.parse(contentUri)
        val orientation = readOrientation(uri)
        val bounds = readBounds(uri)
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.first, bounds.second, maximumLongestEdge)
        }
        val decoded = requireNotNull(resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }) {
            "Image could not be decoded"
        }
        var working = decoded
        try {
            working = boundLongestEdge(working, maximumLongestEdge)
            working = applyOrientation(working, orientation)
            return recognizer.recognize(working)
        } finally {
            if (working !== decoded && !working.isRecycled) working.recycle()
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    private fun readBounds(uri: Uri): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        require(options.outWidth > 0 && options.outHeight > 0) { "Image dimensions are unavailable" }
        return options.outWidth to options.outHeight
    }

    private fun readOrientation(uri: Uri): Int = runCatching {
        resolver.openInputStream(uri)?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun boundLongestEdge(bitmap: Bitmap, maximum: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maximum) return bitmap
        val scale = maximum.toFloat() / longest.toFloat()
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (oriented !== bitmap) bitmap.recycle()
        return oriented
    }

    companion object {
        const val MAXIMUM_LONGEST_EDGE = 2_048

        internal fun calculateInSampleSize(width: Int, height: Int, maximum: Int): Int {
            var sample = 1
            val longest = maxOf(width, height)
            while (longest / sample > maximum) sample *= 2
            return sample
        }
    }
}
