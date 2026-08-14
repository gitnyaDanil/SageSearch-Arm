package com.sagesearch.android

import android.net.Uri
import com.sagesearch.android.data.db.ApprovedSourceEntity
import com.sagesearch.android.data.db.DocumentEntity
import com.sagesearch.android.data.db.SageSearchDatabase
import com.sagesearch.android.model.AnalysisStatus
import com.sagesearch.android.model.SourceKind
import com.sagesearch.android.model.SourceStatus

data class IndexedImage(
    val imageUri: String,
    val analyzedAtMillis: Long,
    val contentKind: String,
    val receiptConfidence: Double,
    val ocrText: String,
    val merchantCandidate: String?,
    val transactionDateText: String?,
    val totalText: String?,
    val total: Double?,
    val currency: String?,
) {
    fun toAnalysisResult() = ImageAnalysisResult(
        contentKind = contentKind,
        receiptConfidence = receiptConfidence,
        ocrText = ocrText,
        receipt = ReceiptFields(
            merchantCandidate = merchantCandidate,
            transactionDateText = transactionDateText,
            totalText = totalText,
            total = total,
            currency = currency,
        ),
    )

    companion object {
        fun from(imageUri: String, result: ImageAnalysisResult) = IndexedImage(
            imageUri = imageUri,
            analyzedAtMillis = System.currentTimeMillis(),
            contentKind = result.contentKind,
            receiptConfidence = result.receiptConfidence,
            ocrText = result.ocrText,
            merchantCandidate = result.receipt.merchantCandidate,
            transactionDateText = result.receipt.transactionDateText,
            totalText = result.receipt.totalText,
            total = result.receipt.total,
            currency = result.receipt.currency,
        )
    }
}

class LegacyIndexedImageStore(
    private val database: SageSearchDatabase,
) {
    suspend fun upsert(image: IndexedImage) {
        val sourceId = database.approvedSourceDao().upsert(
            ApprovedSourceEntity(
                uri = image.imageUri,
                label = Uri.parse(image.imageUri).lastPathSegment ?: "Selected image",
                kind = SourceKind.INDIVIDUAL_FILE.name,
                status = SourceStatus.READY.name,
                discoveredCount = 1,
                indexedCount = 1,
                lastScannedAtMillis = image.analyzedAtMillis,
            ),
        )
        val document = DocumentEntity(
            sourceId = sourceId,
            contentUri = image.imageUri,
            displayName = Uri.parse(image.imageUri).lastPathSegment ?: "Selected image",
            mimeType = "image/*",
            sizeBytes = null,
            modifiedAtMillis = null,
            analyzedAtMillis = image.analyzedAtMillis,
            analysisStatus = AnalysisStatus.INDEXED.name,
            receiptConfidence = image.receiptConfidence,
            ocrText = image.ocrText,
            contentKind = image.contentKind,
            merchant = image.merchantCandidate,
            transactionDateIso = null,
            transactionDateText = image.transactionDateText,
            amountMinor = normalizeAmountMinor(image.total, image.currency),
            amountText = image.totalText,
            currencyCode = image.currency,
            extractionVersion = 1,
        )
        database.documentDao().upsertWithFts(document, document.searchableText())
    }

    suspend fun count(): Int = database.documentDao().count()

    suspend fun search(query: String): List<IndexedImage> =
        database.documentDao().legacySearch(query).map(DocumentEntity::toLegacyIndexedImage)
}

private fun DocumentEntity.searchableText(): String = listOfNotNull(
    displayName,
    ocrText,
    merchant,
    transactionDateIso,
    transactionDateText,
    amountText,
    currencyCode,
).joinToString(" ")

private fun DocumentEntity.toLegacyIndexedImage(): IndexedImage = IndexedImage(
    imageUri = contentUri,
    analyzedAtMillis = analyzedAtMillis,
    contentKind = contentKind,
    receiptConfidence = receiptConfidence,
    ocrText = ocrText,
    merchantCandidate = merchant,
    transactionDateText = transactionDateText,
    totalText = amountText,
    total = amountMinor?.let { minor -> if (currencyCode.equals("IDR", ignoreCase = true)) minor.toDouble() else minor / 100.0 },
    currency = currencyCode,
)

private fun normalizeAmountMinor(total: Double?, currency: String?): Long? = total?.let { value ->
    if (currency.equals("IDR", ignoreCase = true)) value.toLong() else (value * 100.0).toLong()
}
