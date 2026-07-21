package com.askphotos.android

import java.util.Locale

object RetrievalTerms {
    fun normalize(terms: List<String>): List<String> = terms.flatMap { term ->
        term.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{M}\\p{N}]+"))
    }.filter { it.length > 1 }.distinct().take(MAX_TERMS)

    private const val MAX_TERMS = 32
}
