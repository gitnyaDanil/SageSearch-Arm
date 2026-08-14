package com.sagesearch.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageAnalysisInterpreterTest {
    @Test
    fun preservesPictureTextWithoutInventingReceiptFields() {
        val result = ImageAnalysisInterpreter.interpret("Welcome to Bandung")

        assertEquals("picture", result.contentKind)
        assertEquals("Welcome to Bandung", result.ocrText)
        assertEquals(0.0, result.receiptConfidence, 0.001)
        assertNull(result.receipt.merchantCandidate)
        assertNull(result.receipt.transactionDateText)
        assertNull(result.receipt.totalText)
    }

    @Test
    fun treatsBlankRecognitionAsUnknown() {
        val result = ImageAnalysisInterpreter.interpret("  \n  ")

        assertEquals("unknown", result.contentKind)
        assertTrue(result.ocrText.isEmpty())
        assertEquals(ReceiptFields(), result.receipt)
    }

    @Test
    fun retainsProvisionalFieldsForMixedReceiptEvidence() {
        val result = ImageAnalysisInterpreter.interpret("TOTAL Rp 50.000")

        assertEquals("mixed", result.contentKind)
        assertEquals("RP 50.000", result.receipt.totalText?.uppercase())
        assertEquals(50000.0, result.receipt.total ?: 0.0, 0.001)
    }
}
