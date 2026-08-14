package com.sagesearch.android

import kotlin.math.min

object ReceiptHeuristics {
    private val receiptWords = Regex(
        "\\b(receipt|invoice|subtotal|total|tax|change|cash|visa|mastercard|qty|amount|" +
            "struk|nota|faktur|jumlah|tunai|kembali|ppn|harga|kasir|toko|" +
            "membership|member|gym|fitness|sushi|restaurant|shoe|repair|service|" +
            "keanggotaan|restoran|sepatu|perbaikan)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val amountPattern = Regex(
        "(?i)(?:rp\\.?|idr|usd|\\$|€|£)?\\s*([0-9]{1,3}(?:[.,][0-9]{3})+(?:[.,][0-9]{2})?|[0-9]+(?:[.,][0-9]{2})?)",
    )
    private val totalLinePattern = Regex(
        "(?im)^.*\\b(?:grand\\s+total|total|jumlah|amount\\s+due)\\b.*$",
    )
    private val datePattern = Regex(
        "(?i)\\b(?:[0-3]?\\d[/-][01]?\\d[/-](?:19|20)?\\d{2}|" +
            "(?:19|20)\\d{2}[/-][01]?\\d[/-][0-3]?\\d|" +
            "[0-3]?\\d\\s+(?:jan(?:uary|uari)?|feb(?:ruary|ruari)?|mar(?:ch|et)?|apr(?:il)?|" +
            "may|mei|jun(?:e|i)?|jul(?:y|i)?|aug(?:ust)?|agu(?:stus)?|sep(?:tember)?|" +
            "oct(?:ober)?|okt(?:ober)?|nov(?:ember)?|dec(?:ember)?|des(?:ember)?)\\s+(?:19|20)\\d{2}|" +
            "(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|" +
            "aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\s+" +
            "[0-3]?\\d,?\\s+(?:19|20)\\d{2})\\b",
    )

    fun analyze(text: String): Pair<Double, ReceiptFields> {
        val normalized = text.trim()
        if (normalized.isEmpty()) return 0.0 to ReceiptFields()

        val wordHits = receiptWords.findAll(normalized).count()
        val amountHits = amountPattern.findAll(normalized).count()
        val lineCount = normalized.lineSequence().count { it.isNotBlank() }
        val hasTotal = totalLinePattern.containsMatchIn(normalized)

        var score = min(wordHits, 4) * 0.14
        score += min(amountHits, 3) * 0.08
        if (hasTotal) score += 0.18
        if (lineCount >= 4) score += 0.08
        score = score.coerceIn(0.0, 1.0)

        return score to extract(normalized)
    }

    private fun extract(text: String): ReceiptFields {
        val totalLine = totalLinePattern.findAll(text).lastOrNull()?.value
        val amountMatch = totalLine?.let { amountPattern.findAll(it).lastOrNull() }
            ?: amountPattern.findAll(text).lastOrNull()
        val totalText = amountMatch?.value?.trim()
        val currency = when {
            totalText?.contains("rp", ignoreCase = true) == true ||
                totalText?.contains("idr", ignoreCase = true) == true -> "IDR"
            totalText?.contains('$') == true || totalText?.contains("usd", ignoreCase = true) == true -> "USD"
            totalText?.contains('€') == true -> "EUR"
            totalText?.contains('£') == true -> "GBP"
            else -> null
        }

        val merchant = text.lineSequence()
            .map(String::trim)
            .firstOrNull { line ->
                line.length in 3..80 && !amountPattern.containsMatchIn(line) &&
                    !receiptWords.matches(line)
            }
        val transactionDateText = datePattern.find(text)?.value

        return ReceiptFields(
            merchantCandidate = merchant,
            transactionDateIso = transactionDateText?.let(::normalizeDate),
            transactionDateText = transactionDateText,
            totalText = totalText,
            total = totalText?.let(::parseAmount),
            currency = currency,
        )
    }

    internal fun parseAmount(raw: String): Double? {
        var numeric = raw.replace(Regex("[^0-9.,]"), "")
        if (numeric.isBlank()) return null

        val lastComma = numeric.lastIndexOf(',')
        val lastDot = numeric.lastIndexOf('.')
        val decimalIndex = maxOf(lastComma, lastDot)
        val digitsAfter = if (decimalIndex >= 0) numeric.length - decimalIndex - 1 else -1

        numeric = if (digitsAfter == 2) {
            val whole = numeric.substring(0, decimalIndex).replace(Regex("[.,]"), "")
            "$whole.${numeric.substring(decimalIndex + 1)}"
        } else {
            numeric.replace(Regex("[.,]"), "")
        }
        return numeric.toDoubleOrNull()
    }

    internal fun normalizeDate(raw: String): String? {
        val cleaned = raw.trim().replace(",", "").replace(Regex("\\s+"), " ")
        val numeric = Regex("^(\\d{1,4})[/-](\\d{1,2})[/-](\\d{2,4})$").matchEntire(cleaned)
        if (numeric != null) {
            val first = numeric.groupValues[1].toInt()
            val second = numeric.groupValues[2].toInt()
            val third = numeric.groupValues[3].toInt()
            val (year, month, day) = if (first >= 1900) {
                Triple(first, second, third)
            } else {
                Triple(if (third < 100) 2000 + third else third, second, first)
            }
            return isoDate(year, month, day)
        }

        val parts = cleaned.split(' ')
        if (parts.size != 3) return null
        val year = parts[2].toIntOrNull() ?: return null
        val monthFirst = monthNumber(parts[0])
        return if (monthFirst != null) {
            isoDate(year, monthFirst, parts[1].toIntOrNull() ?: return null)
        } else {
            isoDate(year, monthNumber(parts[1]) ?: return null, parts[0].toIntOrNull() ?: return null)
        }
    }

    private fun monthNumber(raw: String): Int? = when (raw.lowercase()) {
        "jan", "january", "januari" -> 1
        "feb", "february", "februari" -> 2
        "mar", "march", "maret" -> 3
        "apr", "april" -> 4
        "may", "mei" -> 5
        "jun", "june", "juni" -> 6
        "jul", "july", "juli" -> 7
        "aug", "august", "agu", "agustus" -> 8
        "sep", "september" -> 9
        "oct", "october", "okt", "oktober" -> 10
        "nov", "november" -> 11
        "dec", "december", "des", "desember" -> 12
        else -> null
    }

    private fun isoDate(year: Int, month: Int, day: Int): String? {
        if (year !in 1900..2100 || month !in 1..12) return null
        val leap = year % 400 == 0 || (year % 4 == 0 && year % 100 != 0)
        val monthDays = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        if (day !in 1..monthDays[month - 1]) return null
        return "%04d-%02d-%02d".format(year, month, day)
    }
}
