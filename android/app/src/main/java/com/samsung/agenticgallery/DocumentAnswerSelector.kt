package com.samsung.agenticgallery

data class DocumentFactSelection(val document: SearchHit, val fact: EvidenceRecord?)

/** Selects exactly the first plan-sorted document so an older fact cannot hide a newer failure. */
object DocumentAnswerSelector {
    fun select(
        hits: List<SearchHit>,
        allowedSourceFields: Set<String> = setOf("document_total"),
    ): DocumentFactSelection? = hits.firstOrNull()?.let { hit ->
        DocumentFactSelection(hit, hit.evidence.firstOrNull { it.sourceField in allowedSourceFields })
    }
}
