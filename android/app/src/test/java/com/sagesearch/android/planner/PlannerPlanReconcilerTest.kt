package com.sagesearch.android.planner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlannerPlanReconcilerTest {
    private val validator = PlannerOutputValidator()
    private val reconciler = PlannerPlanReconciler(
        referenceDate = { "2026-08-14" },
    )

    @Test
    fun deterministicFactsCorrectKnownModelAmbiguities() {
        CASES.forEach { case ->
            val modelPlan = valid(case.modelOutput)
            val expected = valid(case.expected)

            val actual = reconciler.reconcile(case.query, modelPlan)

            assertEquals(case.query, expected, actual)
        }
    }

    @Test
    fun semanticIntentIsPreservedWhenThereIsNoExplicitDeterministicClue() {
        val modelPlan = ValidatedPlannerPlan(textTerms = listOf("shoe repair"), labels = listOf("shoe"))

        val actual = reconciler.reconcile("help me find the shoe repair place", modelPlan)

        assertEquals(modelPlan, actual)
    }

    @Test
    fun unsafeUserTextCannotBePromotedByReconciliation() {
        val original = ValidatedPlannerPlan(contentKinds = listOf("picture"))

        val actual = reconciler.reconcile(
            "images containing the word content://private/file",
            original,
        )

        assertEquals(original, actual)
        assertFalse(PlannerPlanJson.encode(actual).contains("content://"))
    }

    @Test
    fun ambiguousAccumulatedDatesDoNotOverrideTheModelsExplicitReplacement() {
        val april = ValidatedPlannerPlan(
            contentKinds = listOf("receipt"),
            transactionDateRange = StringBounds("2026-04-01", "2026-04-30"),
        )

        val actual = reconciler.reconcile(
            "gym receipt around March 2026 actually April 2026",
            april,
        )

        assertEquals(april, actual)
    }

    private fun valid(raw: String): ValidatedPlannerPlan =
        (validator.validate(raw) as PlanValidationResult.Valid).plan

    private data class Case(val query: String, val modelOutput: String, val expected: String)

    companion object {
        private val CASES = listOf(
            Case(
                "Alfamart receipts from July 2026 over Rp100,000 containing coffee",
                """{"version":1,"textTerms":["coffee"],"contentKinds":["receipt"],"merchant":"Alfamart","amountRangeMinor":{"min":100000},"currencyCode":"IDR","transactionDateRange":{"start":"2026-07-01","end":"2026-07-31"}}""",
                """{"version":1,"textTerms":["coffee"],"contentKinds":["receipt"],"merchant":"Alfamart","amountRangeMinor":{"min":100000},"currencyCode":"IDR","transactionDateRange":{"start":"2026-07-01","end":"2026-07-31"}}""",
            ),
            Case(
                "Find the Toko Maju receipt dated 8 August 2026 with total Rp27.750",
                """{"version":1,"textTerms":["Toko Maju","receipt"],"merchant":"Toko Maju","amountRangeMinor":{"max":27750},"currencyCode":"IDR","transactionDateRange":{"start":"2026-08-08","end":"2026-08-08"}}""",
                """{"version":1,"contentKinds":["receipt"],"merchant":"Toko Maju","amountRangeMinor":{"min":27750,"max":27750},"currencyCode":"IDR","transactionDateRange":{"start":"2026-08-08","end":"2026-08-08"}}""",
            ),
            Case(
                "USD receipts between 10 and 50 dollars",
                """{"version":1,"contentKinds":["receipt"],"amountRangeMinor":{"min":100,"max":500},"currencyCode":"USD"}""",
                """{"version":1,"contentKinds":["receipt"],"amountRangeMinor":{"min":1000,"max":5000},"currencyCode":"USD"}""",
            ),
            Case(
                "Receipts from last month mentioning battery",
                """{"version":1,"textTerms":["battery"],"contentKinds":["receipt"]}""",
                """{"version":1,"textTerms":["battery"],"contentKinds":["receipt"],"transactionDateRange":{"start":"2026-07-01","end":"2026-07-31"}}""",
            ),
            Case(
                "Photos in the Camera album containing conference badge",
                """{"version":1,"contentKinds":["picture"],"labels":["conference badge"],"albumHint":"Camera"}""",
                """{"version":1,"textTerms":["conference badge"],"contentKinds":["picture"],"albumHint":"Camera"}""",
            ),
            Case(
                "Receipts under Rp50.000",
                """{"version":1,"contentKinds":["receipt"],"amountRangeMinor":{"min":50000,"max":50000}}""",
                """{"version":1,"contentKinds":["receipt"],"amountRangeMinor":{"max":50000},"currencyCode":"IDR"}""",
            ),
            Case(
                "Receipts totaling exactly Rp125.000",
                """{"version":1,"contentKinds":["receipt"],"amountRangeMinor":{"min":125000}}""",
                """{"version":1,"contentKinds":["receipt"],"amountRangeMinor":{"min":125000,"max":125000},"currencyCode":"IDR"}""",
            ),
            Case(
                "Photos containing more than one face",
                """{"version":1,"contentKinds":["picture"],"faceFilter":"multiple"}""",
                """{"version":1,"contentKinds":["picture"],"faceFilter":"multiple"}""",
            ),
            Case(
                "Screenshots showing error code 0x80070005",
                """{"version":1,"textTerms":["error code 0x80070005"],"contentKinds":["picture"]}""",
                """{"version":1,"textTerms":["0x80070005"],"contentKinds":["picture"],"labels":["screenshot"]}""",
            ),
            Case(
                "Struk Alfamart bulan Juli 2026 di atas Rp100 ribu",
                """{"version":1,"textTerms":["Struk Alfamart","Juli 2026","Rp100 ribu"],"contentKinds":["receipt"],"merchant":"Alfamart","amountRangeMinor":{"min":100000},"currencyCode":"IDR","transactionDateRange":{"start":"2026-07-01","end":"2026-07-31"}}""",
                """{"version":1,"contentKinds":["receipt"],"merchant":"Alfamart","amountRangeMinor":{"min":100000},"currencyCode":"IDR","transactionDateRange":{"start":"2026-07-01","end":"2026-07-31"}}""",
            ),
            Case(
                "Find images containing the word coffee",
                """{"version":1,"textTerms":["coffee"],"contentKinds":["picture"]}""",
                """{"version":1,"textTerms":["coffee"]}""",
            ),
            Case(
                "Receipt dated 2026-08-08",
                """{"version":1,"transactionDateRange":{"start":"2026-08-08","end":"2026-08-08"}}""",
                """{"version":1,"contentKinds":["receipt"],"transactionDateRange":{"start":"2026-08-08","end":"2026-08-08"}}""",
            ),
            Case(
                "Pictures captured from 1 through 7 August 2026",
                """{"version":1,"mediaDateRange":{"start":"2026-08-01T00:00:00Z","end":"2026-08-07T23:59:59Z"}}""",
                """{"version":1,"contentKinds":["picture"],"mediaDateRange":{"start":"2026-08-01T00:00:00Z","end":"2026-08-07T23:59:59Z"}}""",
            ),
        )
    }
}
