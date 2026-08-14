package com.sagesearch.android.data.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

class DocumentTreeSource(private val resolver: ContentResolver) {
    fun enumerate(treeUri: Uri, limit: Int = 10_000): List<DiscoveredDocument> {
        val pending = ArrayDeque<String>().apply { add(DocumentsContract.getTreeDocumentId(treeUri)) }
        val visited = mutableSetOf<String>()
        val documents = mutableListOf<DiscoveredDocument>()
        while (pending.isNotEmpty() && documents.size < limit) {
            val parentId = pending.removeFirst()
            if (!visited.add(parentId)) continue
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_FLAGS,
            )
            resolver.query(childrenUri, columns, null, null, null)?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(columns[0])
                val name = cursor.getColumnIndexOrThrow(columns[1])
                val mime = cursor.getColumnIndexOrThrow(columns[2])
                val size = cursor.getColumnIndexOrThrow(columns[3])
                val modified = cursor.getColumnIndexOrThrow(columns[4])
                val flags = cursor.getColumnIndexOrThrow(columns[5])
                while (cursor.moveToNext() && documents.size < limit) {
                    val documentId = cursor.getString(id)
                    val mimeType = cursor.getString(mime)
                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        pending.add(documentId)
                    } else if (cursor.getInt(flags) and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT == 0) {
                        documents += DiscoveredDocument(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                            displayName = cursor.getString(name) ?: "Approved file",
                            mimeType = mimeType ?: "application/octet-stream",
                            sizeBytes = if (cursor.isNull(size)) null else cursor.getLong(size),
                            modifiedAtMillis = if (cursor.isNull(modified)) null else cursor.getLong(modified),
                        )
                    }
                }
            }
        }
        return documents
    }
}
