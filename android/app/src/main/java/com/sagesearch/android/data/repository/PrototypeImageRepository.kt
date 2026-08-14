package com.sagesearch.android.data.repository

import android.content.Context
import android.net.Uri
import com.sagesearch.android.AndroidOcrAnalyzer
import com.sagesearch.android.ImageAnalysisResult
import com.sagesearch.android.IndexedImage
import com.sagesearch.android.LegacyIndexedImageStore
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

interface PrototypeImageRepository {
    suspend fun indexedCount(): Int
    suspend fun analyzeAndIndex(contentUri: String): ImageAnalysisResult
    suspend fun search(query: String): List<IndexedImage>
}

class DefaultPrototypeImageRepository(
    context: Context,
    private val store: LegacyIndexedImageStore,
) : PrototypeImageRepository {
    private val analyzer = AndroidOcrAnalyzer(context)

    override suspend fun indexedCount(): Int = store.count()

    override suspend fun analyzeAndIndex(contentUri: String): ImageAnalysisResult {
        val result = suspendCancellableCoroutine { continuation ->
            analyzer.analyze(
                uri = Uri.parse(contentUri),
                onSuccess = { analysis ->
                    if (continuation.isActive) continuation.resume(analysis)
                },
                onError = { error ->
                    if (continuation.isActive) continuation.resumeWithException(error)
                },
            )
        }
        store.upsert(IndexedImage.from(contentUri, result))
        return result
    }

    override suspend fun search(query: String): List<IndexedImage> = store.search(query.trim())
}
