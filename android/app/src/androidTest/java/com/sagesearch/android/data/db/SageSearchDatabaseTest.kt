package com.sagesearch.android.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sagesearch.android.model.AnalysisStatus
import com.sagesearch.android.model.SourceKind
import com.sagesearch.android.model.SourceStatus
import com.sagesearch.android.model.SearchPlan
import com.sagesearch.android.search.DefaultSearchRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SageSearchDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val opened = mutableListOf<SageSearchDatabase>()

    @After
    fun cleanUp() {
        opened.reversed().forEach(SageSearchDatabase::close)
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun migrationFromV1PreservesPrototypeEvidenceAndBuildsFts() = runBlocking {
        context.deleteDatabase(TEST_DATABASE)
        context.openOrCreateDatabase(TEST_DATABASE, Context.MODE_PRIVATE, null).use { legacy ->
            legacy.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `indexed_images` (
                    `imageUri` TEXT NOT NULL,
                    `analyzedAtMillis` INTEGER NOT NULL,
                    `contentKind` TEXT NOT NULL,
                    `receiptConfidence` REAL NOT NULL,
                    `ocrText` TEXT NOT NULL,
                    `merchantCandidate` TEXT,
                    `transactionDateText` TEXT,
                    `totalText` TEXT,
                    `total` REAL,
                    `currency` TEXT,
                    PRIMARY KEY(`imageUri`)
                )
                """.trimIndent(),
            )
            legacy.execSQL(
                """
                INSERT INTO indexed_images VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "content://demo/IMG_184522.jpg",
                    1_786_699_200_000L,
                    "receipt",
                    0.95,
                    "Fitness Center Membership March",
                    "Fitness Center",
                    "2026-03-12",
                    "IDR 200,000",
                    200_000.0,
                    "IDR",
                ),
            )
            legacy.version = 1
        }

        val database = Room.databaseBuilder(context, SageSearchDatabase::class.java, TEST_DATABASE)
            .addMigrations(MIGRATION_1_2)
            .build()
            .also(opened::add)

        val sources = database.approvedSourceDao().list()
        val documents = database.documentDao().list()
        val matches = database.documentSearchDao().searchFts("Fitness", 10)

        assertEquals(1, sources.size)
        assertEquals(SourceKind.INDIVIDUAL_FILE.name, sources.single().kind)
        assertEquals(SourceStatus.READY.name, sources.single().status)
        assertEquals(1, documents.size)
        assertEquals("Fitness Center Membership March", documents.single().ocrText)
        assertEquals("Fitness Center", documents.single().merchant)
        assertEquals("2026-03-12", documents.single().transactionDateText)
        assertEquals(200_000L, documents.single().amountMinor)
        assertEquals("IDR 200,000", documents.single().amountText)
        assertEquals("IDR", documents.single().currencyCode)
        assertEquals(documents.single().id, matches.single().id)
    }

    @Test
    fun documentTransactionsKeepFtsSynchronized() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, SageSearchDatabase::class.java)
            .build()
            .also(opened::add)
        val sourceId = database.approvedSourceDao().upsert(
            ApprovedSourceEntity(
                uri = "content://demo/source",
                label = "Demo",
                kind = SourceKind.INDIVIDUAL_FILE.name,
                status = SourceStatus.READY.name,
                discoveredCount = 1,
                indexedCount = 1,
                lastScannedAtMillis = 1L,
            ),
        )
        val original = DocumentEntity(
            sourceId = sourceId,
            contentUri = "content://demo/receipt",
            displayName = "IMG_0001.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 100L,
            modifiedAtMillis = 1L,
            analyzedAtMillis = 2L,
            analysisStatus = AnalysisStatus.INDEXED.name,
            receiptConfidence = 0.9,
            ocrText = "alpha receipt",
            contentKind = "receipt",
            merchant = "Alpha",
            transactionDateIso = null,
            transactionDateText = null,
            amountMinor = null,
            amountText = null,
            currencyCode = null,
            extractionVersion = 1,
        )

        val id = database.documentDao().upsertWithFts(original, "alpha receipt")
        assertEquals(id, database.documentSearchDao().searchFts("alpha", 10).single().id)

        database.documentDao().upsertWithFts(original.copy(id = id, merchant = "Beta"), "beta receipt")
        assertTrue(database.documentSearchDao().searchFts("alpha", 10).isEmpty())
        assertEquals(id, database.documentSearchDao().searchFts("beta", 10).single().id)

        database.documentDao().deleteWithFts(id)
        assertTrue(database.documentSearchDao().searchFts("beta", 10).isEmpty())
    }

    @Test
    fun queuedDocumentsAreClaimedOnceCheckpointedAndRemainSearchable() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, SageSearchDatabase::class.java)
            .build()
            .also(opened::add)
        val sourceId = database.approvedSourceDao().upsert(
            ApprovedSourceEntity(
                uri = "content://demo/source-claim",
                label = "Claim demo",
                kind = SourceKind.INDIVIDUAL_FILE.name,
                status = SourceStatus.INDEXING.name,
                discoveredCount = 1,
                indexedCount = 0,
                lastScannedAtMillis = 1L,
            ),
        )
        val documentId = database.documentDao().upsertWithFts(
            queuedDocument(sourceId, "content://demo/queued", "opaque-gym-receipt.jpg"),
            "opaque-gym-receipt.jpg",
        )

        val claim = database.documentDao().claimNextQueued(sourceId, 100L)
        assertEquals(documentId, claim?.id)
        assertEquals(AnalysisStatus.ANALYZING.name, claim?.analysisStatus)
        assertEquals(null, database.documentDao().claimNextQueued(sourceId, 101L))

        assertEquals(
            1,
            database.documentDao().completeCheckpoint(
                documentId,
                AnalysisStatus.METADATA_INDEXED.name,
                200L,
                0,
            ),
        )
        assertEquals(0, database.documentDao().pendingCountForSource(sourceId))
        assertEquals(1, database.documentDao().completedCountForSource(sourceId))
        assertEquals(documentId, database.documentSearchDao().searchFts("gym", 10).single().id)
    }

    @Test
    fun staleAnalyzingClaimReturnsToQueueWithoutTouchingFreshClaim() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, SageSearchDatabase::class.java)
            .build()
            .also(opened::add)
        val sourceId = database.approvedSourceDao().upsert(
            ApprovedSourceEntity(
                uri = "content://demo/source-recovery",
                label = "Recovery demo",
                kind = SourceKind.TREE.name,
                status = SourceStatus.INDEXING.name,
                discoveredCount = 2,
                indexedCount = 0,
                lastScannedAtMillis = 1L,
            ),
        )
        database.documentDao().upsertWithFts(
            queuedDocument(sourceId, "content://demo/stale", "stale.pdf"),
            "stale.pdf",
        )
        database.documentDao().upsertWithFts(
            queuedDocument(sourceId, "content://demo/fresh", "fresh.pdf"),
            "fresh.pdf",
        )
        val stale = requireNotNull(database.documentDao().claimNextQueued(sourceId, 100L))
        val fresh = requireNotNull(database.documentDao().claimNextQueued(sourceId, 500L))

        assertEquals(1, database.documentDao().recoverAbandoned(300L))
        assertEquals(AnalysisStatus.QUEUED.name, database.documentDao().findByUri(stale.contentUri)?.analysisStatus)
        assertEquals(AnalysisStatus.ANALYZING.name, database.documentDao().findByUri(fresh.contentUri)?.analysisStatus)
    }

    @Test
    fun extractionFactsAndFtsCommitInOneCheckpoint() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, SageSearchDatabase::class.java)
            .build()
            .also(opened::add)
        val sourceId = database.approvedSourceDao().upsert(
            ApprovedSourceEntity(
                uri = "content://demo/extraction-source",
                label = "Extraction demo",
                kind = SourceKind.INDIVIDUAL_FILE.name,
                status = SourceStatus.INDEXING.name,
                discoveredCount = 1,
                indexedCount = 0,
                lastScannedAtMillis = 1L,
            ),
        )
        database.documentDao().upsertWithFts(
            queuedDocument(sourceId, "content://demo/extracted", "IMG_184522.jpg"),
            "IMG_184522.jpg",
        )
        val claimed = requireNotNull(database.documentDao().claimNextQueued(sourceId, 100L))
        val completed = claimed.copy(
            analyzedAtMillis = 200L,
            analysisStatus = AnalysisStatus.INDEXED.name,
            receiptConfidence = 0.9,
            ocrText = "Fitness Center gym membership March",
            contentKind = "receipt",
            merchant = "Fitness Center",
            transactionDateIso = "2026-03-12",
            transactionDateText = "12 March 2026",
            amountMinor = 200_000L,
            amountText = "Rp 200.000",
            currencyCode = "IDR",
            extractionVersion = 1,
        )

        assertTrue(database.documentDao().completeWithFts(completed, "IMG_184522.jpg Fitness Center gym membership March"))

        val match = database.documentSearchDao().searchFts("membership", 10).single()
        assertEquals("Fitness Center", match.merchant)
        assertEquals("2026-03-12", match.transactionDateIso)
        assertEquals(200_000L, match.amountMinor)
        assertEquals(AnalysisStatus.INDEXED.name, match.analysisStatus)
    }

    @Test
    fun preliminarySearchEscapesTermsAndCapsCandidatePoolAtTwoHundred() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, SageSearchDatabase::class.java)
            .build()
            .also(opened::add)
        val sourceId = database.approvedSourceDao().upsert(
            ApprovedSourceEntity(
                uri = "content://demo/search-bound-source",
                label = "Search bound",
                kind = SourceKind.TREE.name,
                status = SourceStatus.READY.name,
                discoveredCount = 205,
                indexedCount = 205,
                lastScannedAtMillis = 1L,
            ),
        )
        repeat(205) { index ->
            val document = queuedDocument(
                sourceId,
                "content://demo/search-$index",
                "opaque-$index.jpg",
            ).copy(
                analysisStatus = AnalysisStatus.INDEXED.name,
                ocrText = "receipt common token $index",
                contentKind = "receipt",
            )
            database.documentDao().upsertWithFts(document, "${document.displayName} ${document.ocrText}")
        }
        val repository = DefaultSearchRepository(database)

        assertEquals(200, repository.search(SearchPlan(textTerms = listOf("receipt"))).size)
        assertTrue(repository.search("receipt OR * \" )").size <= 200)
        assertTrue(repository.search("   ").isEmpty())
    }

    @Test
    fun structuredAmountFindsOpaqueDocumentAndUnavailableRemovalClearsFts() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, SageSearchDatabase::class.java)
            .build()
            .also(opened::add)
        val sourceId = database.approvedSourceDao().upsert(
            ApprovedSourceEntity(
                uri = "content://demo/structured-source",
                label = "Structured",
                kind = SourceKind.INDIVIDUAL_FILE.name,
                status = SourceStatus.READY.name,
                discoveredCount = 1,
                indexedCount = 1,
                lastScannedAtMillis = 1L,
            ),
        )
        val document = queuedDocument(sourceId, "content://demo/opaque-amount", "IMG_184522.jpg").copy(
            analysisStatus = AnalysisStatus.INDEXED.name,
            amountMinor = 200_000L,
            amountText = "Rp 200.000",
            currencyCode = "IDR",
        )
        val id = database.documentDao().upsertWithFts(document, document.displayName)
        val repository = DefaultSearchRepository(database)

        val matches = repository.search(
            SearchPlan(
                amountMinMinor = 200_000L,
                amountMaxMinor = 200_000L,
                currencyCode = "IDR",
            ),
        )
        assertEquals(id, matches.single().documentId)
        assertEquals("Amount", matches.single().evidence.single().label)

        repository.removeUnavailable(id)
        assertTrue(database.documentSearchDao().searchFts("IMG", 10).isEmpty())
        assertEquals(0, database.documentDao().count())
    }

    private fun queuedDocument(sourceId: Long, uri: String, name: String) = DocumentEntity(
        sourceId = sourceId,
        contentUri = uri,
        displayName = name,
        mimeType = "application/octet-stream",
        sizeBytes = 100L,
        modifiedAtMillis = 1L,
        analyzedAtMillis = 0L,
        analysisStatus = AnalysisStatus.QUEUED.name,
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

    companion object {
        private const val TEST_DATABASE = "sagesearch-migration-test.db"
    }
}
