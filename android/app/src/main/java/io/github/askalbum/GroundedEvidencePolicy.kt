package io.github.anup42.askalbum

/** Keeps candidate-only or cross-scope evidence out of factual answer composition. */
internal object GroundedEvidencePolicy {
    private val directPersonCaptionSources = setOf(
        "semantic_caption",
        "semantic_caption_embedding",
    )
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
            return plan.intent in eventAnswerIntents ||
                plan.intent == QueryIntent.LIST && plan.grouping == Grouping.EVENT
        }
        if (hasPersonVisualCondition(plan)) {
            return record.sourceField == "visual_verification" ||
                isDirectCachedPersonEvidence(record, plan)
        }
        return true
    }

    private fun isDirectCachedPersonEvidence(record: EvidenceRecord, plan: GalleryQueryPlan): Boolean {
        if (record.sourceField !in directPersonCaptionSources) return false
        val clusterId = record.clusterId?.trim()?.takeIf(String::isNotBlank) ?: return false
        val evidenceMediaId = record.evidenceMediaId?.trim()?.takeIf(String::isNotBlank) ?: return false
        val directlyScoped = when (record.scope) {
            SemanticFactScope.MEDIA,
            SemanticFactScope.QUERY_VERIFICATION,
            -> evidenceMediaId == record.mediaId
            SemanticFactScope.EXACT_DUPLICATE_GROUP ->
                record.applicability == SemanticProvenanceApplicability.EXACT_DUPLICATE_SHARED
            else -> false
        }
        if (!directlyScoped) return false

        val conditionedPeople = plan.semanticClauses.mapNotNull { it.relationToPerson?.trim()?.takeIf(String::isNotBlank) }
        val requiredPeople = if (conditionedPeople.isNotEmpty()) {
            conditionedPeople
        } else {
            plan.peopleClauses.filter(PersonClause::mustBePresent).map(PersonClause::personId)
        }
        return requiredPeople.isEmpty() || requiredPeople.any {
            PersonIdentityNormalization.normalize(it) == PersonIdentityNormalization.normalize(clusterId)
        }
    }

    fun hasPersonVisualCondition(plan: GalleryQueryPlan): Boolean = plan.semanticClauses.any {
        it.subject == SemanticSubject.PERSON || it.relationToPerson != null
    }

    fun requiresUncertainty(record: EvidenceRecord): Boolean =
        record.applicability == SemanticProvenanceApplicability.POSSIBLE_INFERENCE

    fun evidencePriority(record: EvidenceRecord): Int = when {
        record.applicability == SemanticProvenanceApplicability.POSSIBLE_INFERENCE -> 3
        record.sourceField == "visual_verification" -> 0
        record.scope == SemanticFactScope.MEDIA || record.scope == SemanticFactScope.QUERY_VERIFICATION -> 1
        else -> 2
    }
}
