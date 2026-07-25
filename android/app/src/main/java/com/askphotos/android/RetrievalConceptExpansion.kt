package com.askphotos.android

import java.util.Locale

/**
 * Bounded, deterministic recall expansion for concepts whose visible evidence is commonly labelled
 * with concrete objects rather than the user's abstract event name.
 */
internal object RetrievalConceptExpansion {
    private data class Concept(
        val evidenceTerms: List<String>,
        val semanticQueries: List<String>,
    )

    private val concepts = mapOf(
        "birthday" to Concept(
            evidenceTerms = listOf("cake", "candle", "candles", "balloon", "balloons", "party", "celebration"),
            semanticQueries = listOf("birthday cake", "birthday candles", "birthday party", "birthday celebration"),
        ),
    )

    fun evidenceTerms(terms: List<String>): List<String> = buildList {
        addAll(terms)
        terms.forEach { term -> addAll(concepts[term.lowercase(Locale.ROOT)]?.evidenceTerms.orEmpty()) }
    }.distinct().take(MAX_TERMS)

    fun semanticQueries(terms: List<String>): List<String> = terms.flatMap { term ->
        concepts[term.lowercase(Locale.ROOT)]?.semanticQueries.orEmpty()
    }.distinctBy { it.lowercase(Locale.ROOT) }.take(MAX_SEMANTIC_QUERIES)

    private const val MAX_TERMS = 32
    private const val MAX_SEMANTIC_QUERIES = 6
}
