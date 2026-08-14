package com.sagesearch.android.search

import java.text.Normalizer
import java.util.Locale

object FtsQueryBuilder {
    private const val MAX_TERM_LENGTH = 64

    fun exactTerm(raw: String): String? {
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
        val phrase = TOKEN.findAll(normalized)
            .map { it.value.take(MAX_TERM_LENGTH) }
            .take(MAX_PHRASE_TERMS)
            .joinToString(" ")
            .take(MAX_TERM_LENGTH)
        if (phrase.isBlank()) return null
        return "\"$phrase\""
    }

    private val TOKEN = Regex("[\\p{L}\\p{N}]+")
    private const val MAX_PHRASE_TERMS = 4
}
