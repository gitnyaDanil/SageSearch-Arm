package com.sagesearch.android.data.storage

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Point
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface DocumentThumbnailLoader {
    suspend fun load(contentUri: String): Bitmap?
}

class AndroidDocumentThumbnailLoader(
    private val resolver: ContentResolver,
) : DocumentThumbnailLoader {
    override suspend fun load(contentUri: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(contentUri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.loadThumbnail(uri, Size(160, 160), null)
            } else {
                @Suppress("DEPRECATION")
                DocumentsContract.getDocumentThumbnail(resolver, uri, Point(160, 160), null)
            }
        }.getOrNull()
    }
}
