package com.sagesearch.android.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `approved_sources` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `uri` TEXT NOT NULL,
                `label` TEXT NOT NULL,
                `kind` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `discoveredCount` INTEGER NOT NULL,
                `indexedCount` INTEGER NOT NULL,
                `lastScannedAtMillis` INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_approved_sources_uri` ON `approved_sources` (`uri`)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `documents` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sourceId` INTEGER NOT NULL,
                `contentUri` TEXT NOT NULL,
                `displayName` TEXT NOT NULL,
                `mimeType` TEXT NOT NULL,
                `sizeBytes` INTEGER,
                `modifiedAtMillis` INTEGER,
                `analyzedAtMillis` INTEGER NOT NULL,
                `analysisStatus` TEXT NOT NULL,
                `receiptConfidence` REAL NOT NULL,
                `ocrText` TEXT NOT NULL,
                `contentKind` TEXT NOT NULL,
                `merchant` TEXT,
                `transactionDateIso` TEXT,
                `transactionDateText` TEXT,
                `amountMinor` INTEGER,
                `amountText` TEXT,
                `currencyCode` TEXT,
                `extractionVersion` INTEGER NOT NULL,
                FOREIGN KEY(`sourceId`) REFERENCES `approved_sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_documents_sourceId` ON `documents` (`sourceId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_documents_contentUri` ON `documents` (`contentUri`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_documents_analysisStatus` ON `documents` (`analysisStatus`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_documents_modifiedAtMillis` ON `documents` (`modifiedAtMillis`)")
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `documents_fts` USING FTS4(`searchableText`, tokenize=unicode61)")

        db.execSQL(
            """
            INSERT INTO `approved_sources` (
                `uri`, `label`, `kind`, `status`, `discoveredCount`, `indexedCount`, `lastScannedAtMillis`
            )
            SELECT
                `imageUri`, 'Imported image', 'INDIVIDUAL_FILE', 'READY', 1, 1, `analyzedAtMillis`
            FROM `indexed_images`
            """.trimIndent(),
        )

        db.execSQL(
            """
            INSERT INTO `documents` (
                `sourceId`, `contentUri`, `displayName`, `mimeType`, `sizeBytes`, `modifiedAtMillis`,
                `analyzedAtMillis`, `analysisStatus`, `receiptConfidence`, `ocrText`, `contentKind`,
                `merchant`, `transactionDateIso`, `transactionDateText`, `amountMinor`, `amountText`,
                `currencyCode`, `extractionVersion`
            )
            SELECT
                source.`id`, old.`imageUri`, old.`imageUri`, 'image/*', NULL, NULL,
                old.`analyzedAtMillis`, 'INDEXED', old.`receiptConfidence`, old.`ocrText`, old.`contentKind`,
                old.`merchantCandidate`, NULL, old.`transactionDateText`,
                CASE
                    WHEN old.`total` IS NULL THEN NULL
                    WHEN UPPER(COALESCE(old.`currency`, '')) = 'IDR' THEN CAST(ROUND(old.`total`) AS INTEGER)
                    ELSE CAST(ROUND(old.`total` * 100.0) AS INTEGER)
                END,
                old.`totalText`, old.`currency`, 1
            FROM `indexed_images` old
            JOIN `approved_sources` source ON source.`uri` = old.`imageUri`
            """.trimIndent(),
        )

        db.execSQL(
            """
            INSERT INTO `documents_fts` (`rowid`, `searchableText`)
            SELECT
                `id`, TRIM(
                    COALESCE(`displayName`, '') || ' ' || COALESCE(`ocrText`, '') || ' ' ||
                    COALESCE(`merchant`, '') || ' ' || COALESCE(`transactionDateText`, '') || ' ' ||
                    COALESCE(`amountText`, '') || ' ' || COALESCE(`currencyCode`, '')
                )
            FROM `documents`
            """.trimIndent(),
        )

        db.execSQL("DROP TABLE `indexed_images`")
    }
}
