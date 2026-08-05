package io.github.anup42.askalbum

internal object EventExpansionPolicy {
    fun itemPredicateIds(
        terms: List<String>,
        lexicalIds: Set<String>,
        semanticIds: Set<String>,
        captionIds: Set<String>,
        captionEmbeddingIds: Set<String>,
    ): Set<String> = buildSet {
        if (terms.any(String::isNotBlank)) addAll(lexicalIds)
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
