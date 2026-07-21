package com.askphotos.android

/** Converts a planner result into an app-owned, validated refinement of one persisted result set. */
class ResultSetPlanPatchResolver(
    private val planValidator: GalleryQueryPlanValidator = GalleryQueryPlanValidator(),
) {
    fun createAndApply(
        compiledPlan: GalleryQueryPlan,
        state: ConversationSearchState,
    ): Pair<PlanPatch, GalleryQueryPlan> {
        val resultSetId = requireNotNull(state.activeResultSetId) { "Follow-up requires an active result set" }
        require(state.activeResultIds.isNotEmpty()) { "Follow-up result set is empty" }
        require(compiledPlan.baseResultIds == state.activeResultIds) { "Planner used a stale follow-up scope" }

        val detached = compiledPlan.copy(baseResultIds = null)
        val patch = PlanPatch(
            baseResultSetId = resultSetId,
            changedFields = changedFields(detached),
            replacementPlan = detached,
        )
        return patch to apply(patch, state)
    }

    fun apply(patch: PlanPatch, state: ConversationSearchState): GalleryQueryPlan {
        require(patch.version == 1) { "Unsupported PlanPatch version" }
        require(patch.baseResultSetId.matches(RESULT_SET_ID)) { "Invalid result-set reference" }
        require(patch.baseResultSetId == state.activeResultSetId) { "Stale result-set reference" }
        require(state.activeResultIds.isNotEmpty()) { "Follow-up result set is empty" }
        require(patch.changedFields.isNotEmpty() && patch.changedFields.size <= MAX_CHANGED_FIELDS) {
            "PlanPatch must contain bounded changes"
        }
        require(patch.changedFields.all(ALLOWED_CHANGED_FIELDS::contains)) { "Unsupported PlanPatch field" }
        require(patch.replacementPlan.baseResultIds == null) { "PlanPatch cannot contain media IDs" }
        val resolved = patch.replacementPlan.copy(baseResultIds = state.activeResultIds)
        return planValidator.requireValid(resolved, state.activeResultIds)
    }

    private fun changedFields(plan: GalleryQueryPlan): Set<String> = buildSet {
        if (plan.intent != QueryIntent.FIND_MEDIA) add("intent")
        if (plan.mediaScope != MediaScope.ALL) add("mediaScope")
        if (plan.filter != FilterExpression.True) add("filter")
        if (plan.semanticClauses.isNotEmpty() || plan.terms.isNotEmpty()) add("semanticClauses")
        if (plan.peopleClauses.isNotEmpty()) add("peopleClauses")
        if (plan.ocrClause != null) add("ocrClause")
        if (plan.grouping != Grouping.NONE) add("grouping")
        if (plan.aggregation != null) add("aggregation")
        if (plan.sort != SortSpec.RELEVANCE) add("sort")
        if (plan.verification != VerificationPolicy.AUTO) add("verification")
        if (plan.answerMode != AnswerMode.RESULTS_AND_SUMMARY) add("answerMode")
        if (plan.place != null) add("place")
        if (plan.limit != 100) add("limit")
        if (isEmpty()) add("scope")
    }

    private companion object {
        const val MAX_CHANGED_FIELDS = 13
        val RESULT_SET_ID = Regex("rs_[A-Za-z0-9_-]{8,80}")
        val ALLOWED_CHANGED_FIELDS = setOf(
            "intent", "mediaScope", "filter", "semanticClauses", "peopleClauses", "ocrClause",
            "grouping", "aggregation", "sort", "verification", "answerMode", "place", "limit", "scope",
        )
    }
}
