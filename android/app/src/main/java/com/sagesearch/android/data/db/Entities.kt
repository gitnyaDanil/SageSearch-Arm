package com.sagesearch.android.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "approved_sources",
    indices = [Index(value = ["uri"], unique = true)],
)
data class ApprovedSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val label: String,
    val kind: String,
    val status: String,
    val discoveredCount: Int,
    val indexedCount: Int,
    val lastScannedAtMillis: Long?,
)

@Entity(
    tableName = "documents",
    foreignKeys = [
        ForeignKey(
            entity = ApprovedSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sourceId"]),
        Index(value = ["contentUri"], unique = true),
        Index(value = ["analysisStatus"]),
        Index(value = ["modifiedAtMillis"]),
    ],
)
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val contentUri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val modifiedAtMillis: Long?,
    val analyzedAtMillis: Long,
    val analysisStatus: String,
    val receiptConfidence: Double,
    val ocrText: String,
    val contentKind: String,
    val merchant: String?,
    val transactionDateIso: String?,
    val transactionDateText: String?,
    val amountMinor: Long?,
    val amountText: String?,
    val currencyCode: String?,
    val extractionVersion: Int,
)
