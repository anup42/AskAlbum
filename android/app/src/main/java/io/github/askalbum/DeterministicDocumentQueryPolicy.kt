package io.github.anup42.askalbum

/**
 * Keeps allowlisted OCR fact operations deterministic and independent from ranked top-K retrieval.
 * The repository still uses normal lexical/semantic retrieval for free-form document discovery.
 */
internal object DeterministicDocumentQueryPolicy {
    private val supportedIntents = setOf(
        QueryIntent.ANSWER_FACT,
        QueryIntent.DOCUMENT_QA,
        QueryIntent.SUM,
        QueryIntent.MIN_MAX,
    )

    fun field(plan: GalleryQueryPlan): OcrFactField? {
        if (plan.intent !in supportedIntents) return null
        return OcrFactAllowlist.resolve(plan.ocrClause?.requestedField ?: plan.aggregation?.field)
    }

    fun requiresCompleteHits(plan: GalleryQueryPlan): Boolean =
        field(plan) != null && plan.intent in setOf(QueryIntent.SUM, QueryIntent.MIN_MAX)
}
