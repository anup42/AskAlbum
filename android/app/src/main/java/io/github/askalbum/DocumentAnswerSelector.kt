package io.github.anup42.askalbum

data class DocumentFactSelection(val document: SearchHit, val fact: EvidenceRecord?)

/** Selects exactly the first plan-sorted document so an older fact cannot hide a newer failure. */
object DocumentAnswerSelector {
    fun select(
        hits: List<SearchHit>,
        allowedSourceFields: Set<String> = setOf("document_total"),
        sort: SortSpec = SortSpec.RELEVANCE,
    ): DocumentFactSelection? = order(hits, sort).firstOrNull()?.let { hit ->
        DocumentFactSelection(hit, hit.evidence.firstOrNull { it.sourceField in allowedSourceFields })
    }

    private fun order(hits: List<SearchHit>, sort: SortSpec): List<SearchHit> = when (sort) {
        SortSpec.CAPTURE_TIME_DESC -> hits.sortedWith(
            compareByDescending<SearchHit> {
                it.item.capturedAt ?: it.item.modifiedAt ?: Long.MIN_VALUE
            }.thenByDescending { it.score }.thenBy { it.item.id },
        )
        SortSpec.CAPTURE_TIME_ASC -> hits.sortedWith(
            compareBy<SearchHit> {
                it.item.capturedAt ?: it.item.modifiedAt ?: Long.MAX_VALUE
            }.thenByDescending { it.score }.thenBy { it.item.id },
        )
        SortSpec.QUALITY -> hits.sortedWith(
            compareByDescending<SearchHit> { it.item.qualityScore ?: 0f }
                .thenByDescending { it.score }.thenBy { it.item.id },
        )
        else -> hits
    }
}
