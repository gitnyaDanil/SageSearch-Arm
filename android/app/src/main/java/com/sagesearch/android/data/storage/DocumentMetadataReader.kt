package com.sagesearch.android.data.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns

data class DiscoveredDocument(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val modifiedAtMillis: Long?,
)

class DocumentMetadataReader(private val resolver: ContentResolver) {
    fun read(uri: Uri): DiscoveredDocument {
        var displayName: String? = null
        var size: Long? = null
        var modified: Long? = null
        val projection = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                displayName = cursor.stringOrNull(OpenableColumns.DISPLAY_NAME)
                size = cursor.longOrNull(OpenableColumns.SIZE)
                modified = cursor.longOrNull(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            }
        }
        return DiscoveredDocument(
            uri = uri,
            displayName = displayName ?: uri.lastPathSegment ?: "Approved file",
            mimeType = resolver.getType(uri) ?: "application/octet-stream",
            sizeBytes = size,
            modifiedAtMillis = modified,
        )
    }
}

private fun android.database.Cursor.stringOrNull(column: String): String? {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getString(index) else null
}

private fun android.database.Cursor.longOrNull(column: String): Long? {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getLong(index) else null
}
