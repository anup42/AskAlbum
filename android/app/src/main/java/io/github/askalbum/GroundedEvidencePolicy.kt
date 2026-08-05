package io.github.anup42.askalbum

/** Keeps candidate-only or cross-scope evidence out of factual answer composition. */
internal object GroundedEvidencePolicy {
    private val contextualCaptionSources = setOf(
        "semantic_caption_candidate_expansion",
        "semantic_caption_embedding_context",
    )
    private val eventAnswerIntents = setOf(
        QueryIntent.EVENT_SUMMARY,
        QueryIntent.TIMELINE,
        QueryIntent.COMPARE,
    )

    fun allow(record: EvidenceRecord, plan: GalleryQueryPlan): Boolean {
        if (record.mediaId.isBlank() || record.sourceField in contextualCaptionSources) return false
        if (record.applicability in SemanticProvenanceApplicability.NON_CONFIRMING - SemanticProvenanceApplicability.POSSIBLE_INFERENCE) {
            return false
        }
        if (record.scope == SemanticFactScope.VISUAL_GROUP) return false
        if (record.scope == SemanticFactScope.EXACT_DUPLICATE_GROUP &&
            record.applicability != SemanticProvenanceApplicability.EXACT_DUPLICATE_SHARED
        ) {
            return false
        }
        if (record.scope == SemanticFactScope.EVENT || record.sourceField == "event") {
            return plan.intent in eventAnswerIntents
        }
        if (hasPersonVisualCondition(plan)) {
            return record.sourceField == "visual_verification"
        }
        return true
    }

    fun hasPersonVisualCondition(plan: GalleryQueryPlan): Boolean = plan.semanticClauses.any {
        it.subject == SemanticSubject.PERSON || it.relationToPerson != null
    }

    fun requiresUncertainty(record: EvidenceRecord): Boolean =
        record.applicability == SemanticProvenanceApplicability.POSSIBLE_INFERENCE
}
