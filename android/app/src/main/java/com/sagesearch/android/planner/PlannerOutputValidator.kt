package com.sagesearch.android.planner

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.math.BigDecimal
import java.text.Normalizer
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

class PlannerOutputValidator {
    private val decoder = StrictJsonObjectDecoder()
    fun validate(rawOutput: String): PlanValidationResult = try {
        PlanValidationResult.Valid(validateObject(decoder.decode(rawOutput)))
    } catch (invalid: InvalidPlannerOutput) {
        PlanValidationResult.Invalid(invalid.reason)
    } catch (_: Throwable) {
        PlanValidationResult.Invalid(PlannerRejection.MALFORMED_JSON)
    }

    private fun validateObject(json: JsonObject): ValidatedPlannerPlan {
        if (json.keySet().any { it !in ALLOWED_FIELDS }) reject(PlannerRejection.UNKNOWN_FIELD)
        val version = integer(json["version"] ?: reject(PlannerRejection.BAD_SCHEMA_VERSION))
        if (version != 1L) reject(PlannerRejection.BAD_SCHEMA_VERSION)
        val textTerms = stringList(json["textTerms"], MAX_LIST_ITEMS)
        val contentKinds = stringList(json["contentKinds"], MAX_CONTENT_KINDS)
        if (contentKinds.any { it !in CONTENT_KINDS }) reject(PlannerRejection.UNSUPPORTED_VALUE)
        val labels = stringList(json["labels"], MAX_LIST_ITEMS)
        val merchant = optionalString(json["merchant"], MAX_MERCHANT_LENGTH)
        val albumHint = optionalString(json["albumHint"], MAX_ALBUM_LENGTH)
        val currency = optionalString(json["currencyCode"], 3)?.also {
            if (!CURRENCY.matches(it)) reject(PlannerRejection.UNSUPPORTED_VALUE)
        }
        val faceFilter = optionalString(json["faceFilter"], 16)?.also {
            if (it !in FACE_FILTERS) reject(PlannerRejection.UNSUPPORTED_VALUE)
        }
        return ValidatedPlannerPlan(
            textTerms = textTerms,
            contentKinds = contentKinds,
            merchant = merchant,
            amountRangeMinor = amountRange(json["amountRangeMinor"]),
            currencyCode = currency,
            transactionDateRange = dateRange(json["transactionDateRange"]),
            mediaDateRange = instantRange(json["mediaDateRange"]),
            labels = labels,
            faceFilter = faceFilter,
            albumHint = albumHint,
        )
    }

    private fun stringList(element: JsonElement?, maximum: Int): List<String> {
        if (element == null) return emptyList()
        if (!element.isJsonArray) reject(PlannerRejection.WRONG_TYPE)
        val array = element.asJsonArray
        if (array.size() > maximum) reject(PlannerRejection.TOO_MANY_VALUES)
        val values = array.map { item ->
            if (!item.isJsonPrimitive || !item.asJsonPrimitive.isString) reject(PlannerRejection.WRONG_TYPE)
            safeString(item.asString, MAX_TERM_LENGTH)
        }
        if (values.map { it.lowercase(Locale.ROOT) }.distinct().size != values.size) {
            reject(PlannerRejection.DUPLICATE_VALUE)
        }
        return values
    }

    private fun optionalString(element: JsonElement?, maximum: Int): String? {
        if (element == null) return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) reject(PlannerRejection.WRONG_TYPE)
        return safeString(element.asString, maximum)
    }

    private fun safeString(raw: String, maximum: Int): String {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .trim()
            .replace(WHITESPACE, " ")
        if (normalized.isEmpty()) reject(PlannerRejection.EMPTY_VALUE)
        if (normalized.length > maximum) reject(PlannerRejection.TOO_MANY_VALUES)
        if (normalized.any { it.isISOControl() }) reject(PlannerRejection.UNSAFE_VALUE)
        if (UNSAFE_PUNCTUATION.containsMatchIn(normalized) ||
            URI_OR_PATH.containsMatchIn(normalized) ||
            SQL_LANGUAGE.containsMatchIn(normalized)
        ) {
            reject(PlannerRejection.UNSAFE_VALUE)
        }
        return normalized
    }

    private fun amountRange(element: JsonElement?): LongBounds? {
        val objectValue = rangeObject(element) ?: return null
        val minimum = objectValue["min"]?.let(::integer)
        val maximum = objectValue["max"]?.let(::integer)
        if (minimum == null && maximum == null) reject(PlannerRejection.INVALID_RANGE)
        if ((minimum ?: 0L) < 0L || (maximum ?: 0L) < 0L) reject(PlannerRejection.INVALID_RANGE)
        if (minimum != null && maximum != null && minimum > maximum) reject(PlannerRejection.INVALID_RANGE)
        if ((minimum ?: 0L) > MAX_AMOUNT || (maximum ?: 0L) > MAX_AMOUNT) {
            reject(PlannerRejection.INVALID_RANGE)
        }
        return LongBounds(minimum, maximum)
    }

    private fun dateRange(element: JsonElement?): StringBounds? {
        val objectValue = temporalObject(element) ?: return null
        val start = objectValue["start"]?.let(::date)
        val end = objectValue["end"]?.let(::date)
        if (start == null && end == null) reject(PlannerRejection.INVALID_RANGE)
        if (start != null && end != null && start > end) reject(PlannerRejection.INVALID_RANGE)
        return StringBounds(start, end)
    }

    private fun instantRange(element: JsonElement?): StringBounds? {
        val objectValue = temporalObject(element) ?: return null
        val start = objectValue["start"]?.let(::instant)
        val end = objectValue["end"]?.let(::instant)
        if (start == null && end == null) reject(PlannerRejection.INVALID_RANGE)
        if (start != null && end != null && parseInstant(start) > parseInstant(end)) {
            reject(PlannerRejection.INVALID_RANGE)
        }
        return StringBounds(start, end)
    }

    private fun rangeObject(element: JsonElement?): JsonObject? =
        objectWithAllowedFields(element, RANGE_FIELDS)

    private fun temporalObject(element: JsonElement?): JsonObject? =
        objectWithAllowedFields(element, TEMPORAL_FIELDS)

    private fun objectWithAllowedFields(element: JsonElement?, allowedFields: Set<String>): JsonObject? {
        if (element == null) return null
        if (!element.isJsonObject) reject(PlannerRejection.WRONG_TYPE)
        return element.asJsonObject.also { objectValue ->
            if (objectValue.keySet().any { it !in allowedFields }) reject(PlannerRejection.UNKNOWN_FIELD)
        }
    }

    private fun integer(element: JsonElement): Long {
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) reject(PlannerRejection.WRONG_TYPE)
        return try {
            val number = BigDecimal(element.asString)
            if (number.stripTrailingZeros().scale() > 0) reject(PlannerRejection.WRONG_TYPE)
            number.longValueExact()
        } catch (invalid: InvalidPlannerOutput) {
            throw invalid
        } catch (_: Throwable) {
            reject(PlannerRejection.WRONG_TYPE)
        }
    }

    private fun date(element: JsonElement): String {
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) reject(PlannerRejection.WRONG_TYPE)
        val raw = element.asString
        val match = ISO_DATE.matchEntire(raw) ?: reject(PlannerRejection.INVALID_DATE)
        validateCalendar(match.groupValues[1], match.groupValues[2], match.groupValues[3], PlannerRejection.INVALID_DATE)
        return raw
    }

    private fun instant(element: JsonElement): String {
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) reject(PlannerRejection.WRONG_TYPE)
        val raw = element.asString
        parseInstant(raw)
        return raw
    }

    private fun parseInstant(raw: String): InstantParts {
        val match = ISO_INSTANT.matchEntire(raw) ?: reject(PlannerRejection.INVALID_INSTANT)
        validateCalendar(match.groupValues[1], match.groupValues[2], match.groupValues[3], PlannerRejection.INVALID_INSTANT)
        val hour = match.groupValues[4].toInt()
        val minute = match.groupValues[5].toInt()
        val second = match.groupValues[6].toInt()
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) reject(PlannerRejection.INVALID_INSTANT)
        val fraction = match.groupValues[7].padEnd(9, '0')
        return InstantParts(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
            hour,
            minute,
            second,
            fraction,
        )
    }

    private fun validateCalendar(year: String, month: String, day: String, reason: PlannerRejection) {
        try {
            GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
                isLenient = false
                clear()
                set(year.toInt(), month.toInt() - 1, day.toInt())
                timeInMillis
            }
        } catch (_: Throwable) {
            reject(reason)
        }
    }

    private data class InstantParts(
        val year: Int,
        val month: Int,
        val day: Int,
        val hour: Int,
        val minute: Int,
        val second: Int,
        val fraction: String,
    ) : Comparable<InstantParts> {
        override fun compareTo(other: InstantParts): Int = compareValuesBy(
            this,
            other,
            InstantParts::year,
            InstantParts::month,
            InstantParts::day,
            InstantParts::hour,
            InstantParts::minute,
            InstantParts::second,
            InstantParts::fraction,
        )
    }

    companion object {
        private const val MAX_LIST_ITEMS = 12
        private const val MAX_CONTENT_KINDS = 4
        private const val MAX_TERM_LENGTH = 80
        private const val MAX_MERCHANT_LENGTH = 120
        private const val MAX_ALBUM_LENGTH = 160
        private const val MAX_AMOUNT = 1_000_000_000_000_000L
        private val ALLOWED_FIELDS = setOf(
            "version", "textTerms", "contentKinds", "merchant", "amountRangeMinor",
            "currencyCode", "transactionDateRange", "mediaDateRange", "labels",
            "faceFilter", "albumHint",
        )
        private val RANGE_FIELDS = setOf("min", "max")
        private val TEMPORAL_FIELDS = setOf("start", "end")
        private val CONTENT_KINDS = setOf("receipt", "picture", "mixed", "unknown")
        private val FACE_FILTERS = setOf("none", "any", "exactly_one", "multiple")
        private val CURRENCY = Regex("[A-Z]{3}")
        private val WHITESPACE = Regex("\\s+")
        private val UNSAFE_PUNCTUATION = Regex("(?:[?*;`]|--|/\\*|\\*/)")
        private val URI_OR_PATH = Regex("(?i)(?:\\b(?:content|file|https?|ftp):|[a-z]:[\\\\/]|[/\\\\])")
        private val SQL_LANGUAGE = Regex(
            "(?i)\\b(?:select|insert|update|delete|drop|alter|pragma|attach|detach|union|join|where|sqlite_master)\\b",
        )
        private val ISO_DATE = Regex("(\\d{4})-(\\d{2})-(\\d{2})")
        private val ISO_INSTANT = Regex(
            "(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,9}))?Z",
        )
    }
}
