package io.github.anup42.askalbum

/** Combines ranked and deterministic evidence without dropping either channel for one media item. */
internal object GroundedAnswerEvidenceHits {
    fun merge(primary: List<SearchHit>, deterministic: List<SearchHit>): List<SearchHit> =
        (primary + deterministic)
            .groupBy { it.item.id }
            .values
            .map { sameMedia ->
                val first = sameMedia.first()
                first.copy(
                    evidence = sameMedia
                        .flatMap(SearchHit::evidence)
                        .distinctBy(EvidenceRecord::id),
                )
            }
}
