package com.sagesearch.android.search

import android.content.Context
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sagesearch.android.data.db.ApprovedSourceEntity
import com.sagesearch.android.data.db.SageSearchDatabase
import com.sagesearch.android.model.SourceKind
import com.sagesearch.android.model.SourceStatus
import kotlin.math.ceil
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreliminarySearchBenchmarkTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun tenThousandDocumentSearchMeetsLatencyAndRetrievalGates() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, SageSearchDatabase::class.java).build()
        try {
            val sourceId = database.approvedSourceDao().upsert(
                ApprovedSourceEntity(
                    uri = "content://synthetic/task11",
                    label = "Task 11 synthetic source",
                    kind = SourceKind.TREE.name,
                    status = SourceStatus.READY.name,
                    discoveredCount = DOCUMENT_COUNT,
                    indexedCount = DOCUMENT_COUNT,
                    lastScannedAtMillis = 1L,
                ),
            )
            seedSyntheticDocuments(database, sourceId)
            val repository = DefaultSearchRepository(database)
            val retrievalCases = listOf(
                RetrievalCase("gym-march", "gym membership around March", GYM_ID),
                RetrievalCase("sushi", "sushi receipt", SUSHI_ID),
                RetrievalCase("shoe-repair", "shoe repair around Rp150.000", SHOE_REPAIR_ID),
            )

            val retrievalResults = retrievalCases.map { case ->
                val results = repository.search(case.query)
                val rank = results.indexOfFirst { it.documentId == case.expectedDocumentId }
                    .takeIf { it >= 0 }
                    ?.plus(1)
                assertEquals("${case.id} should rank first", 1, rank)
                JSONObject()
                    .put("id", case.id)
                    .put("rank", rank)
                    .put("result_count", results.size)
            }

            repeat(WARM_UP_RUNS) { repository.search(retrievalCases.first().query) }
            val latencyMillis = List(RECORDED_RUNS) {
                val started = SystemClock.elapsedRealtimeNanos()
                val results = repository.search(retrievalCases.first().query)
                assertEquals(GYM_ID, results.first().documentId)
                (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
            }.sorted()
            val p50 = percentile(latencyMillis, 0.50)
            val p95 = percentile(latencyMillis, 0.95)
            assertTrue("Preliminary p50 $p50 ms exceeds the 500 ms target", p50 < 500.0)

            val report = JSONObject()
                .put("evidence_kind", "synthetic on-device preliminary retrieval")
                .put("document_count", DOCUMENT_COUNT)
                .put("candidate_cap", DefaultSearchRepository.MAX_CANDIDATES)
                .put("warm_up_runs", WARM_UP_RUNS)
                .put("recorded_runs", RECORDED_RUNS)
                .put("latency_ms_p50", p50)
                .put("latency_ms_p95", p95)
                .put("latency_ms_min", latencyMillis.first())
                .put("latency_ms_max", latencyMillis.last())
                .put("retrieval_case_count", retrievalCases.size)
                .put("retrieval_top_1_rate", 1.0)
                .put("retrieval_top_3_rate", 1.0)
                .put("retrieval", JSONArray(retrievalResults))
                .put(
                    "limitations",
                    JSONArray()
                        .put("Documents and queries are synthetic and exercise the bounded Room/FTS retrieval path.")
                        .put("The timing excludes OCR, model inference, and UI rendering."),
                )
            context.filesDir.resolve(REPORT_FILE).writeText(report.toString(2))
        } finally {
            database.close()
        }
    }

    private fun seedSyntheticDocuments(database: SageSearchDatabase, sourceId: Long) {
        val sqlite = database.openHelper.writableDatabase
        val insertDocument = sqlite.compileStatement(
            """
            INSERT INTO documents(
                id, sourceId, contentUri, displayName, mimeType, sizeBytes, modifiedAtMillis,
                analyzedAtMillis, analysisStatus, receiptConfidence, ocrText, contentKind,
                merchant, transactionDateIso, transactionDateText, amountMinor, amountText,
                currencyCode, extractionVersion
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        )
        val insertFts = sqlite.compileStatement(
            "INSERT INTO documents_fts(rowid, searchableText) VALUES (?, ?)",
        )
        sqlite.beginTransaction()
        try {
            repeat(DOCUMENT_COUNT) { zeroBased ->
                val id = zeroBased.toLong() + 1L
                val fixture = fixture(id)
                insertDocument.clearBindings()
                insertDocument.bindLong(1, id)
                insertDocument.bindLong(2, sourceId)
                insertDocument.bindString(3, "content://synthetic/task11/$id")
                insertDocument.bindString(4, fixture.displayName)
                insertDocument.bindString(5, "image/png")
                insertDocument.bindLong(6, 64_000L)
                insertDocument.bindLong(7, id)
                insertDocument.bindLong(8, id)
                insertDocument.bindString(9, "INDEXED")
                insertDocument.bindDouble(10, fixture.receiptConfidence)
                insertDocument.bindString(11, fixture.ocrText)
                insertDocument.bindString(12, fixture.contentKind)
                insertDocument.bindNullableString(13, fixture.merchant)
                insertDocument.bindNullableString(14, fixture.dateIso)
                insertDocument.bindNullableString(15, fixture.dateText)
                insertDocument.bindNullableLong(16, fixture.amountMinor)
                insertDocument.bindNullableString(17, fixture.amountText)
                insertDocument.bindNullableString(18, fixture.currency)
                insertDocument.bindLong(19, 1L)
                insertDocument.executeInsert()

                insertFts.clearBindings()
                insertFts.bindLong(1, id)
                insertFts.bindString(2, "${fixture.displayName} ${fixture.ocrText} ${fixture.merchant.orEmpty()}")
                insertFts.executeInsert()
            }
            sqlite.setTransactionSuccessful()
        } finally {
            sqlite.endTransaction()
        }
    }

    private fun fixture(id: Long): Fixture = when (id) {
        GYM_ID -> Fixture(
            displayName = "IMG_20260312_184522.png",
            ocrText = "FITNESS CENTER MEMBERSHIP RECEIPT MONTHLY GYM Date 12 March 2026 TOTAL Rp 200.000",
            contentKind = "receipt",
            receiptConfidence = 0.98,
            merchant = "FITNESS CENTER",
            dateIso = "2026-03-12",
            dateText = "12 March 2026",
            amountMinor = 200_000L,
            amountText = "Rp 200.000",
            currency = "IDR",
        )
        SUSHI_ID -> Fixture(
            displayName = "scan_000274.png",
            ocrText = "SAKURA SUSHI RECEIPT omakase dinner TOTAL Rp 325.000",
            contentKind = "receipt",
            receiptConfidence = 0.96,
            merchant = "SAKURA SUSHI",
            dateIso = "2026-05-10",
            dateText = "10 May 2026",
            amountMinor = 325_000L,
            amountText = "Rp 325.000",
            currency = "IDR",
        )
        SHOE_REPAIR_ID -> Fixture(
            displayName = "document_8419.png",
            ocrText = "QUICK SHOE REPAIR service receipt TOTAL Rp 150.000",
            contentKind = "receipt",
            receiptConfidence = 0.97,
            merchant = "QUICK SHOE REPAIR",
            dateIso = "2026-04-22",
            dateText = "22 April 2026",
            amountMinor = 150_000L,
            amountText = "Rp 150.000",
            currency = "IDR",
        )
        else -> {
            val receipt = id % 20L == 0L
            Fixture(
                displayName = "opaque_$id.dat",
                ocrText = if (receipt) "generic store receipt archive item $id" else "archive document item $id",
                contentKind = if (receipt) "receipt" else "metadata",
                receiptConfidence = if (receipt) 0.6 else 0.0,
            )
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteStatement.bindNullableString(index: Int, value: String?) {
        if (value == null) bindNull(index) else bindString(index, value)
    }

    private fun androidx.sqlite.db.SupportSQLiteStatement.bindNullableLong(index: Int, value: Long?) {
        if (value == null) bindNull(index) else bindLong(index, value)
    }

    private fun percentile(values: List<Double>, proportion: Double): Double {
        val index = values.lastIndex * proportion
        val lower = index.toInt()
        val upper = ceil(index).toInt()
        if (lower == upper) return values[lower]
        val weight = index - lower
        return values[lower] * (1.0 - weight) + values[upper] * weight
    }

    private data class RetrievalCase(val id: String, val query: String, val expectedDocumentId: Long)

    private data class Fixture(
        val displayName: String,
        val ocrText: String,
        val contentKind: String,
        val receiptConfidence: Double,
        val merchant: String? = null,
        val dateIso: String? = null,
        val dateText: String? = null,
        val amountMinor: Long? = null,
        val amountText: String? = null,
        val currency: String? = null,
    )

    companion object {
        private const val DOCUMENT_COUNT = 10_000
        private const val WARM_UP_RUNS = 3
        private const val RECORDED_RUNS = 25
        private const val GYM_ID = 101L
        private const val SUSHI_ID = 202L
        private const val SHOE_REPAIR_ID = 303L
        const val REPORT_FILE = "task11-preliminary-search-report.json"
    }
}
