package com.sagesearch.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptHeuristicsTest {
    @Test
    fun detectsIndonesianReceiptAndExtractsTotal() {
        val text = """
            TOKO MAJU
            08/08/2026
            Kopi       Rp 25.000
            Subtotal   Rp 25.000
            PPN        Rp 2.750
            TOTAL      Rp 27.750
            Tunai      Rp 30.000
            Kembali    Rp 2.250
        """.trimIndent()

        val (confidence, receipt) = ReceiptHeuristics.analyze(text)

        assertTrue(confidence >= 0.45)
        assertEquals("TOKO MAJU", receipt.merchantCandidate)
        assertEquals("RP 27.750", receipt.totalText?.uppercase())
        assertEquals(27750.0, receipt.total ?: 0.0, 0.001)
        assertEquals("IDR", receipt.currency)
        assertEquals("08/08/2026", receipt.transactionDateText)
    }

    @Test
    fun treatsLandscapeCaptionAsPicture() {
        val (confidence, _) = ReceiptHeuristics.analyze("Sunset over the mountain")
        assertTrue(confidence < 0.45)
    }

    @Test
    fun normalizesIndonesianGymMembershipReceipt() {
        val (confidence, receipt) = ReceiptHeuristics.analyze(
            """
            PUSAT FITNESS NUSANTARA
            KEANGGOTAAN GYM
            12 Maret 2026
            Jumlah Rp 200.000
            TOTAL Rp 200.000
            """.trimIndent(),
        )

        assertTrue(confidence >= 0.45)
        assertEquals("PUSAT FITNESS NUSANTARA", receipt.merchantCandidate)
        assertEquals("2026-03-12", receipt.transactionDateIso)
        assertEquals(200_000.0, receipt.total ?: 0.0, 0.001)
        assertEquals("IDR", receipt.currency)
    }

    @Test
    fun normalizesEnglishSushiReceiptWithUsdMinorUnitsInput() {
        val (confidence, receipt) = ReceiptHeuristics.analyze(
            """
            SAKURA SUSHI HOUSE
            Restaurant receipt
            March 14, 2026
            Subtotal USD 23.00
            GRAND TOTAL USD 25.50
            """.trimIndent(),
        )

        assertTrue(confidence >= 0.45)
        assertEquals("2026-03-14", receipt.transactionDateIso)
        assertEquals(25.50, receipt.total ?: 0.0, 0.001)
        assertEquals("USD", receipt.currency)
    }

    @Test
    fun recognizesShoeRepairServiceFixture() {
        val (confidence, receipt) = ReceiptHeuristics.analyze(
            """
            QUICK SHOE REPAIR
            Service invoice
            2026-04-02
            Repair leather sole 35.00
            TOTAL $35.00
            """.trimIndent(),
        )

        assertTrue(confidence >= 0.45)
        assertEquals("2026-04-02", receipt.transactionDateIso)
        assertEquals(35.0, receipt.total ?: 0.0, 0.001)
        assertEquals("USD", receipt.currency)
    }

    @Test
    fun rejectsImpossibleCalendarDate() {
        assertEquals(null, ReceiptHeuristics.normalizeDate("31/02/2026"))
    }
}
