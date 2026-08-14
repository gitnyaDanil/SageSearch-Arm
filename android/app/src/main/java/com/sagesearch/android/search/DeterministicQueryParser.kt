package com.sagesearch.android.search

import com.sagesearch.android.ReceiptHeuristics
import com.sagesearch.android.model.SearchPlan
import kotlin.math.max
import java.util.Locale

class DeterministicQueryParser {
    fun parse(rawQuery: String): SearchPlan {
        val query = rawQuery.trim()
        if (query.isEmpty()) return SearchPlan()
        val lowered = query.lowercase(Locale.ROOT)
        val dateText = DATE.find(query)?.value
        val dateIso = dateText?.let(ReceiptHeuristics::normalizeDate)
        val amount = parseAmount(query)
        val allTokens = TOKEN.findAll(lowered).map { it.value }.toList()
        val terms = allTokens.asSequence()
            .filter { it.length > 1 }
            .filterNot(STOP_WORDS::contains)
            .filterNot(CURRENCY_WORDS::contains)
            .filterNot { token -> token.all(Char::isDigit) && (dateText?.contains(token) == true || amount != null) }
            .distinct()
            .take(MAX_TERMS)
            .toList()
        val receiptIntent = allTokens.any(RECEIPT_WORDS::contains)

        return SearchPlan(
            textTerms = terms,
            dateFromIso = dateIso,
            dateToIso = dateIso,
            amountMinMinor = amount?.minimumMinor,
            amountMaxMinor = amount?.maximumMinor,
            currencyCode = amount?.currencyCode ?: currencyIn(query),
            receiptIntent = receiptIntent,
        )
    }

    private fun parseAmount(query: String): ParsedAmount? {
        val marked = MARKED_AMOUNT.find(query)
        val contextual = AMOUNT_CONTEXT.find(query)
        val match = marked ?: contextual ?: return null
        val raw = match.value
        val value = ReceiptHeuristics.parseAmount(raw) ?: return null
        val currency = currencyIn(raw)
        val hasTwoDecimalDigits = Regex("[.,]\\d{2}$").containsMatchIn(raw.trim())
        val normalized = when {
            currency == "IDR" -> value.toLong()
            currency != null -> (value * 100.0).toLong()
            hasTwoDecimalDigits -> (value * 100.0).toLong()
            else -> value.toLong()
        }
        val isApproximate = APPROXIMATE.containsMatchIn(query)
        val tolerance = if (isApproximate) max(1L, normalized / 10L) else 0L
        return ParsedAmount(
            minimumMinor = (normalized - tolerance).coerceAtLeast(0L),
            maximumMinor = normalized + tolerance,
            currencyCode = currency,
        )
    }

    private fun currencyIn(raw: String): String? = when {
        raw.contains("idr", ignoreCase = true) || Regex("(?i)\\brp\\.?").containsMatchIn(raw) -> "IDR"
        raw.contains("usd", ignoreCase = true) || raw.contains('$') -> "USD"
        raw.contains('€') -> "EUR"
        raw.contains('£') -> "GBP"
        else -> null
    }

    private data class ParsedAmount(
        val minimumMinor: Long,
        val maximumMinor: Long,
        val currencyCode: String?,
    )

    companion object {
        private const val MAX_TERMS = 12
        private val TOKEN = Regex("[\\p{L}\\p{N}]+")
        private val DATE = Regex(
            "\\b(?:[0-3]?\\d[/-][01]?\\d[/-](?:19|20)?\\d{2}|(?:19|20)\\d{2}[/-][01]?\\d[/-][0-3]?\\d)\\b",
        )
        private val MARKED_AMOUNT = Regex(
            "(?i)(?:rp\\.?|idr|usd|\\$|€|£)\\s*[0-9][0-9.,]*",
        )
        private val AMOUNT_CONTEXT = Regex(
            "(?i)\\b(?:around|about|amount|total|jumlah|sekitar)\\s+[0-9][0-9.,]*",
        )
        private val APPROXIMATE = Regex("(?i)\\b(?:around|about|sekitar|approximately)\\b")
        private val STOP_WORDS = setOf(
            "a", "an", "and", "around", "about", "approximately", "the", "my", "me", "show", "find",
            "file", "document", "from", "with", "that", "this", "of", "for", "please", "sekitar", "cari",
            "yang", "dengan", "dari", "saya",
        )
        private val CURRENCY_WORDS = setOf("rp", "idr", "usd", "eur", "gbp")
        private val RECEIPT_WORDS = setOf("receipt", "invoice", "struk", "nota", "faktur")
    }
}
