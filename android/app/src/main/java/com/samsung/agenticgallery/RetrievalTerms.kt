package com.samsung.agenticgallery

import java.util.Locale

object RetrievalTerms {
    fun normalize(terms: List<String>): List<String> = terms.flatMap { term ->
        term.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{M}\\p{N}]+"))
    }.filter { it.length > 1 }.distinct().take(MAX_TERMS)

    fun forExecution(terms: List<String>, reviewedPeopleFilterApplied: Boolean): List<String> =
        normalize(terms).filterNot { term ->
            term in GENERIC_MEDIA_TERMS || reviewedPeopleFilterApplied && term in REVIEWED_IDENTITY_TERMS
        }

    private const val MAX_TERMS = 32
    private val GENERIC_MEDIA_TERMS = setOf(
        "image", "images", "photo", "photos", "pic", "pics", "picture", "pictures",
    )
    private val REVIEWED_IDENTITY_TERMS = setOf(
        "me", "wife", "husband", "spouse", "partner", "mother", "mom", "mum", "father", "dad",
        "brother", "sister", "child", "son", "daughter", "friend",
    )
}
