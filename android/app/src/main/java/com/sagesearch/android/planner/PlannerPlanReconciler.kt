package com.sagesearch.android.planner

import java.util.GregorianCalendar
import java.util.Locale

/**
 * Applies only facts that can be read deterministically from the user's words.
 * Gemma still supplies semantic intent; arithmetic, calendar boundaries, and
 * explicit OCR/type clues are kept in trusted application code.
 */
class PlannerPlanReconciler(
    private val referenceDate: () -> String,
    private val validator: PlannerOutputValidator = PlannerOutputValidator(),
) {
    fun reconcile(rawQuery: String, modelPlan: ValidatedPlannerPlan): ValidatedPlannerPlan {
        val query = rawQuery
            .filterNot { it.isISOControl() && !it.isWhitespace() }
            .trim()
            .take(MAX_QUERY_CHARACTERS)
        if (query.isEmpty()) return modelPlan

        val receiptIntent = RECEIPT_CUE.containsMatchIn(query)
        val allFormatOcrIntent = ALL_FORMAT_OCR_CUE.containsMatchIn(query)
        val pictureIntent = PICTURE_CUE.containsMatchIn(query)
        val ocrTerm = explicitOcrTerm(query)
        val contentKinds = when {
            allFormatOcrIntent -> emptyList()
            receiptIntent -> listOf("receipt")
            pictureIntent -> listOf("picture")
            else -> modelPlan.contentKinds
        }
        val amount = explicitAmount(query)
        val date = explicitDate(query)
        val transactionRange = when {
            receiptIntent && date != null -> StringBounds(date.start.iso(), date.end.iso())
            else -> modelPlan.transactionDateRange
        }
        val mediaRange = when {
            !receiptIntent && pictureIntent && date != null -> StringBounds(
                "${date.start.iso()}T00:00:00Z",
                "${date.end.iso()}T23:59:59Z",
            )
            else -> modelPlan.mediaDateRange
        }
        val textTerms = when {
            ocrTerm != null -> listOf(ocrTerm)
            receiptIntent -> modelPlan.textTerms.filterNot { term ->
                isStructuredReceiptTerm(term, modelPlan.merchant)
            }
            else -> modelPlan.textTerms
        }
        val labels = modelPlan.labels
            .filterNot { label -> ocrTerm != null && label.equals(ocrTerm, ignoreCase = true) }
            .toMutableList()
            .apply {
                if (SCREENSHOT_CUE.containsMatchIn(query) && none { it.equals("screenshot", true) }) {
                    add("screenshot")
                }
            }

        val candidate = modelPlan.copy(
            textTerms = textTerms,
            contentKinds = contentKinds,
            amountRangeMinor = amount?.bounds ?: modelPlan.amountRangeMinor,
            currencyCode = amount?.currency ?: explicitCurrency(query) ?: modelPlan.currencyCode,
            transactionDateRange = transactionRange,
            mediaDateRange = mediaRange,
            labels = labels,
        )
        return when (val checked = validator.validate(PlannerPlanJson.encode(candidate))) {
            is PlanValidationResult.Valid -> checked.plan
            is PlanValidationResult.Invalid -> modelPlan
        }
    }

    private fun explicitOcrTerm(query: String): String? {
        val match = STRONG_OCR_CUES.firstNotNullOfOrNull { it.find(query) }
            ?: if (RECEIPT_CUE.containsMatchIn(query) || ALBUM_CUE.containsMatchIn(query)) {
                GENERIC_CONTAINING_CUE.find(query)
            } else {
                null
            }
            ?: return null
        return match.groupValues[1].trim().trimEnd('.', ',', ';').takeIf(String::isNotEmpty)
    }

    private fun isStructuredReceiptTerm(term: String, merchant: String?): Boolean {
        val value = term.trim()
        return RECEIPT_CUE.containsMatchIn(value) ||
            merchant?.let { value.equals(it, ignoreCase = true) } == true ||
            (MONTH_YEAR_CUE.containsMatchIn(value)) ||
            (CURRENCY_CUE.containsMatchIn(value) && value.any(Char::isDigit))
    }

    private fun explicitCurrency(query: String): String? = when {
        IDR_CUE.containsMatchIn(query) -> "IDR"
        USD_CUE.containsMatchIn(query) -> "USD"
        else -> null
    }

    private fun explicitAmount(query: String): AmountClue? {
        BETWEEN_USD.find(query)?.let { match ->
            val minimum = dollarsToCents(match.groupValues[1]) ?: return null
            val maximum = dollarsToCents(match.groupValues[2]) ?: return null
            return AmountClue(LongBounds(minimum, maximum), "USD")
        }
        val idrMatches = IDR_AMOUNT.findAll(query).toList()
        if (idrMatches.size != 1) return null
        val match = idrMatches.single()
        val base = match.groupValues[1].filter(Char::isDigit).toLongOrNull() ?: return null
        val multiplier = when (match.groupValues[2].lowercase(Locale.ROOT)) {
            "ribu", "rb" -> 1_000L
            "juta", "jt" -> 1_000_000L
            else -> 1L
        }
        val value = runCatching { Math.multiplyExact(base, multiplier) }.getOrNull() ?: return null
        val bounds = when {
            UNDER_CUE.containsMatchIn(query) -> LongBounds(null, value)
            OVER_CUE.containsMatchIn(query) -> LongBounds(value, null)
            EXACT_CUE.containsMatchIn(query) -> LongBounds(value, value)
            else -> return null
        }
        return AmountClue(bounds, "IDR")
    }

    private fun dollarsToCents(raw: String): Long? = runCatching {
        raw.replace(',', '.')
            .toBigDecimalOrNull()
            ?.movePointRight(2)
            ?.longValueExact()
    }.getOrNull()

    private fun explicitDate(query: String): DateClue? {
        val temporalMentionCount = LAST_MONTH_CUE.findAll(query).count() +
            ISO_DATE.findAll(query).count() + MONTH_YEAR.findAll(query).count()
        if (temporalMentionCount > 1) return null
        if (LAST_MONTH_CUE.containsMatchIn(query)) {
            val reference = parseIsoDate(referenceDate()) ?: return null
            val month = if (reference.month == 1) 12 else reference.month - 1
            val year = if (reference.month == 1) reference.year - 1 else reference.year
            return monthDateClue(year, month)
        }
        ISO_DATE.find(query)?.groupValues?.get(1)?.let { raw ->
            parseIsoDate(raw)?.let { return DateClue(it, it) }
        }
        DAY_RANGE.find(query)?.let { match ->
            val month = monthNumber(match.groupValues[3]) ?: return@let
            val year = match.groupValues[4].toIntOrNull() ?: return@let
            val start = localDate(year, month, match.groupValues[1]) ?: return@let
            val end = localDate(year, month, match.groupValues[2]) ?: return@let
            if (start.sortKey <= end.sortKey) return DateClue(start, end)
        }
        DAY_MONTH_YEAR.find(query)?.let { match ->
            val month = monthNumber(match.groupValues[2]) ?: return@let
            val year = match.groupValues[3].toIntOrNull() ?: return@let
            localDate(year, month, match.groupValues[1])?.let { return DateClue(it, it) }
        }
        MONTH_YEAR.find(query)?.let { match ->
            val month = monthNumber(match.groupValues[1]) ?: return@let
            val year = match.groupValues[2].toIntOrNull() ?: return@let
            return monthDateClue(year, month)
        }
        return null
    }

    private fun parseIsoDate(raw: String): DateParts? {
        val parts = raw.split('-')
        if (parts.size != 3) return null
        return localDate(parts[0].toIntOrNull(), parts[1].toIntOrNull(), parts[2].toIntOrNull())
    }

    private fun localDate(year: Int, month: Int, day: String): DateParts? =
        localDate(year, month, day.toIntOrNull())

    private fun localDate(year: Int?, month: Int?, day: Int?): DateParts? {
        if (year == null || month == null || day == null || year !in 1970..2100 || month !in 1..12) {
            return null
        }
        if (day !in 1..daysInMonth(year, month)) return null
        return DateParts(year, month, day)
    }

    private fun monthDateClue(year: Int, month: Int): DateClue? {
        val start = localDate(year, month, 1) ?: return null
        val end = localDate(year, month, daysInMonth(year, month)) ?: return null
        return DateClue(start, end)
    }

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        2 -> if (GregorianCalendar().isLeapYear(year)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    private fun monthNumber(raw: String): Int? = MONTHS[raw.lowercase(Locale.ROOT)]

    private data class AmountClue(val bounds: LongBounds, val currency: String)
    private data class DateClue(val start: DateParts, val end: DateParts)
    private data class DateParts(val year: Int, val month: Int, val day: Int) {
        val sortKey: Int = year * 10_000 + month * 100 + day
        fun iso(): String = String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
    }

    companion object {
        private const val MAX_QUERY_CHARACTERS = 500
        private val RECEIPT_CUE = Regex("(?i)\\b(?:receipt|receipts|invoice|invoices|struk|nota|faktur)\\b")
        private val PICTURE_CUE = Regex("(?i)\\b(?:photo|photos|picture|pictures|image|images|screenshot|screenshots|captured|face|faces)\\b")
        private val SCREENSHOT_CUE = Regex("(?i)\\bscreenshots?\\b")
        private val ALBUM_CUE = Regex("(?i)\\balbum\\b")
        private val ALL_FORMAT_OCR_CUE = Regex("(?i)\\bimages?\\s+containing\\s+the\\s+word\\b")
        private val STRONG_OCR_CUES = listOf(
            Regex("(?i)\\bvisible\\s+words?\\s+(.+)$"),
            Regex("(?i)\\bmentioning\\s+(.+)$"),
            Regex("(?i)\\bshowing\\s+error\\s+code\\s+([\\p{L}\\p{N}_-]+)"),
            Regex("(?i)\\bcontaining\\s+the\\s+word\\s+(.+)$"),
        )
        private val GENERIC_CONTAINING_CUE = Regex("(?i)\\bcontaining\\s+(.+)$")
        private val CURRENCY_CUE = Regex("(?i)(?:\\brp\\.?|\\bidr\\b|\\busd\\b|\\bdollars?\\b)")
        private val IDR_CUE = Regex("(?i)(?:\\brp\\.?|\\bidr\\b)")
        private val USD_CUE = Regex("(?i)(?:\\busd\\b|\\bdollars?\\b)")
        private val IDR_AMOUNT = Regex("(?i)\\brp\\.?\\s*([0-9][0-9.,]*)(?:\\s*(ribu|rb|juta|jt))?")
        private val BETWEEN_USD = Regex("(?i)\\bbetween\\s+([0-9]+(?:[.,][0-9]+)?)\\s+and\\s+([0-9]+(?:[.,][0-9]+)?)\\s+dollars?\\b")
        private val UNDER_CUE = Regex("(?i)\\b(?:under|below|less\\s+than|di\\s+bawah)\\b")
        private val OVER_CUE = Regex("(?i)\\b(?:over|above|more\\s+than|di\\s+atas|lebih\\s+dari)\\b")
        private val EXACT_CUE = Regex("(?i)\\b(?:exactly|total|totaling|totalling)\\b")
        private val LAST_MONTH_CUE = Regex("(?i)\\blast\\s+month\\b")
        private val ISO_DATE = Regex("\\b(20[0-9]{2}-[01][0-9]-[0-3][0-9])\\b")
        private val DAY_RANGE = Regex("(?i)\\b([0-3]?[0-9])\\s+(?:through|to|until|hingga|sampai)\\s+([0-3]?[0-9])\\s+([\\p{L}]+)\\s+(20[0-9]{2})\\b")
        private val DAY_MONTH_YEAR = Regex("(?i)\\b([0-3]?[0-9])\\s+([\\p{L}]+)\\s+(20[0-9]{2})\\b")
        private val MONTH_YEAR = Regex("(?i)\\b([\\p{L}]+)\\s+(20[0-9]{2})\\b")
        private val MONTH_YEAR_CUE = Regex("(?i)\\b(?:january|february|march|april|may|june|july|august|september|october|november|december|januari|februari|maret|mei|juni|juli|agustus|oktober|november|desember)\\s+20[0-9]{2}\\b")
        private val MONTHS = mapOf(
            "january" to 1, "february" to 2, "march" to 3, "april" to 4,
            "may" to 5, "june" to 6, "july" to 7, "august" to 8,
            "september" to 9, "october" to 10, "november" to 11, "december" to 12,
            "januari" to 1, "februari" to 2, "maret" to 3, "mei" to 5,
            "juni" to 6, "juli" to 7, "agustus" to 8, "oktober" to 10,
            "november" to 11, "desember" to 12,
        )
    }
}
