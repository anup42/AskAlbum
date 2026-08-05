package io.github.anup42.askalbum

import java.text.Normalizer
import java.util.Locale

/** One local, Unicode-safe representation for reviewed-person lookup. */
internal object PersonIdentityNormalization {
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim()
        .replace(Regex("\\s+"), " ")
        .lowercase(Locale.ROOT)

    fun containsIdentityTerm(normalizedQuery: String, rawTerm: String): Boolean {
        val query = normalize(normalizedQuery)
        val term = normalize(rawTerm)
        if (query.isBlank() || term.isBlank()) return false
        return Regex(
            "(^|[^\\p{L}\\p{M}\\p{N}])${Regex.escape(term)}([^\\p{L}\\p{M}\\p{N}]|$)",
        ).containsMatchIn(query)
    }
}
