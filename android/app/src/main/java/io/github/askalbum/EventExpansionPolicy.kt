package io.github.anup42.askalbum

import java.util.Locale

internal object EventExpansionPolicy {
    fun itemPredicateTerms(plan: GalleryQueryPlan): List<String> {
        val scopeTerms = buildSet {
            plan.place?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotBlank)?.let(::add)
            plan.comparisonScopes.asSequence()
                .map { it.trim().lowercase(Locale.ROOT) }
                .filter(String::isNotBlank)
                .forEach(::add)
        }
        return plan.terms
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter(String::isNotBlank)
            .filterNot { it in scopeTerms }
            .distinct()
    }

    fun itemPredicateQueries(plan: GalleryQueryPlan): List<String> {
        val scopeTerms = buildSet {
            plan.place?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotBlank)?.let(::add)
            plan.comparisonScopes.asSequence()
                .map { it.trim().lowercase(Locale.ROOT) }
                .filter(String::isNotBlank)
                .forEach(::add)
        }
        return buildList {
            addAll(itemPredicateTerms(plan))
            plan.semanticClauses
                .filter { it.polarity == Polarity.POSITIVE }
                .flatMap { listOfNotNull(it.text, it.canonicalText) }
                .map { it.trim() }
                .filter(String::isNotBlank)
                .filterNot { it.lowercase(Locale.ROOT) in scopeTerms }
                .forEach(::add)
        }.distinctBy { it.lowercase(Locale.ROOT) }
    }

    fun itemPredicateIds(
        predicateTerms: List<String>,
        lexicalIds: Set<String>,
        semanticIds: Set<String>,
        captionIds: Set<String>,
        captionEmbeddingIds: Set<String>,
    ): Set<String> = buildSet {
        if (predicateTerms.any(String::isNotBlank)) addAll(lexicalIds)
        addAll(semanticIds)
        addAll(captionIds)
        addAll(captionEmbeddingIds)
    }

    fun mediaIdsForSearch(
        rawEventMediaIds: List<String>,
        itemPredicateIds: Set<String>,
        allowContextualExpansion: Boolean,
    ): List<String> = if (allowContextualExpansion) {
        rawEventMediaIds
    } else {
        rawEventMediaIds.filter(itemPredicateIds::contains)
    }
}
