package com.askphotos.android

/** Converts a planner result into an app-owned, validated refinement of one persisted result set. */
class ResultSetPlanPatchResolver(
    private val planValidator: GalleryQueryPlanValidator = GalleryQueryPlanValidator(),
) {
    fun createAndApply(
        compiledPlan: GalleryQueryPlan,
        state: ConversationSearchState,
        previousPlan: GalleryQueryPlan? = null,
    ): Pair<PlanPatch, GalleryQueryPlan> {
        val resultSetId = requireNotNull(state.activeResultSetId) { "Follow-up requires an active result set" }
        require(state.activeResultIds.isNotEmpty()) { "Follow-up result set is empty" }
        require(compiledPlan.baseResultIds == state.activeResultIds) { "Planner used a stale follow-up scope" }

        val detached = normalizeDirectives(compiledPlan.copy(baseResultIds = null))
        val fields = changedFields(detached)
        val patch = PlanPatch(
            version = 2,
            baseResultSetId = resultSetId,
            changedFields = fields,
            replacementPlan = detached,
            operations = typedOperations(fields, previousPlan, detached),
        )
        return patch to apply(patch, state)
    }

    fun apply(patch: PlanPatch, state: ConversationSearchState): GalleryQueryPlan {
        require(patch.version in 1..2) { "Unsupported PlanPatch version" }
        require(patch.baseResultSetId.matches(RESULT_SET_ID)) { "Invalid result-set reference" }
        require(patch.baseResultSetId == state.activeResultSetId) { "Stale result-set reference" }
        require(state.activeResultIds.isNotEmpty()) { "Follow-up result set is empty" }
        require(patch.changedFields.isNotEmpty() && patch.changedFields.size <= MAX_CHANGED_FIELDS) {
            "PlanPatch must contain bounded changes"
        }
        require(patch.changedFields.all(ALLOWED_CHANGED_FIELDS::contains)) { "Unsupported PlanPatch field" }
        require(patch.replacementPlan.baseResultIds == null) { "PlanPatch cannot contain media IDs" }
        if (patch.version == 2) {
            require(patch.operations.isNotEmpty() && patch.operations.size <= MAX_CHANGED_FIELDS) {
                "Typed PlanPatch operations must be bounded"
            }
            require(patch.operations.map(PlanPatchOperation::field).distinct().size == patch.operations.size) {
                "Typed PlanPatch fields must be unique"
            }
        }
        val resolved = patch.replacementPlan.copy(baseResultIds = state.activeResultIds)
        return planValidator.requireValid(resolved, state.activeResultIds)
    }

    private fun normalizeDirectives(plan: GalleryQueryPlan): GalleryQueryPlan {
        val query = plan.originalQuery.trim()
        if (!EXCLUSION_DIRECTIVE.containsMatchIn(query)) return plan
        val predicate = query.replaceFirst(EXCLUSION_DIRECTIVE, "").trim().trimEnd('.', '?', '!')
        if (predicate.isBlank()) return plan
        val negative = SemanticClause(
            text = predicate,
            canonicalText = predicate,
            polarity = Polarity.NEGATIVE,
            hardness = ConstraintStrength.HARD,
        )
        return plan.copy(
            semanticClauses = (plan.semanticClauses.map(SemanticPolarityNormalizer::normalize) + negative)
                .distinctBy { it.text.lowercase() to it.polarity },
            terms = plan.terms.filterNot { it in EXCLUSION_WORDS },
            verification = VerificationPolicy.REQUIRED,
        )
    }

    private fun typedOperations(
        fields: Set<String>,
        previousPlan: GalleryQueryPlan?,
        replacement: GalleryQueryPlan,
    ): List<PlanPatchOperation> = fields.map { field ->
        val typed = typedField(field, replacement)
        val type = when {
            REMOVE_DIRECTIVE.containsMatchIn(replacement.originalQuery) -> PlanPatchOperationType.REMOVE
            previousPlan == null || !isActive(previousPlan, typed) -> PlanPatchOperationType.ADD
            else -> PlanPatchOperationType.REPLACE
        }
        PlanPatchOperation(type, typed)
    }.distinctBy(PlanPatchOperation::field)

    private fun typedField(field: String, plan: GalleryQueryPlan): PlanPatchField = when (field) {
        "intent" -> PlanPatchField.INTENT
        "mediaScope" -> PlanPatchField.MEDIA_KIND
        "filter" -> when {
            containsFilter(plan.filter) { it is FilterExpression.TimeRange } -> PlanPatchField.TIME
            containsFilter(plan.filter) { it is FilterExpression.MediaKindIs } -> PlanPatchField.MEDIA_KIND
            else -> PlanPatchField.FILTER
        }
        "semanticClauses" -> PlanPatchField.SEMANTIC_CLAUSES
        "peopleClauses" -> PlanPatchField.PEOPLE
        "ocrClause" -> PlanPatchField.OCR
        "grouping" -> PlanPatchField.GROUPING
        "aggregation" -> PlanPatchField.AGGREGATION
        "sort" -> PlanPatchField.SORT
        "verification" -> PlanPatchField.VERIFICATION
        "answerMode" -> PlanPatchField.ANSWER_MODE
        "place" -> PlanPatchField.PLACE
        "limit" -> PlanPatchField.LIMIT
        else -> PlanPatchField.SCOPE
    }

    private fun isActive(plan: GalleryQueryPlan, field: PlanPatchField): Boolean = when (field) {
        PlanPatchField.INTENT -> plan.intent != QueryIntent.FIND_MEDIA
        PlanPatchField.TIME -> containsFilter(plan.filter) { it is FilterExpression.TimeRange }
        PlanPatchField.MEDIA_KIND -> plan.mediaScope != MediaScope.ALL ||
            containsFilter(plan.filter) { it is FilterExpression.MediaKindIs }
        PlanPatchField.FILTER -> plan.filter != FilterExpression.True
        PlanPatchField.PEOPLE -> plan.peopleClauses.isNotEmpty()
        PlanPatchField.PLACE -> !plan.place.isNullOrBlank()
        PlanPatchField.SEMANTIC_CLAUSES -> plan.semanticClauses.isNotEmpty() || plan.terms.isNotEmpty()
        PlanPatchField.OCR -> plan.ocrClause != null
        PlanPatchField.SORT -> plan.sort != SortSpec.RELEVANCE
        PlanPatchField.GROUPING -> plan.grouping != Grouping.NONE
        PlanPatchField.AGGREGATION -> plan.aggregation != null
        PlanPatchField.VERIFICATION -> plan.verification != VerificationPolicy.AUTO
        PlanPatchField.ANSWER_MODE -> plan.answerMode != AnswerMode.RESULTS_AND_SUMMARY
        PlanPatchField.LIMIT -> plan.limit != 100
        PlanPatchField.SCOPE -> plan.baseResultIds != null
    }

    private fun containsFilter(
        filter: FilterExpression,
        predicate: (FilterExpression) -> Boolean,
    ): Boolean = when {
        predicate(filter) -> true
        filter is FilterExpression.And -> filter.clauses.any { containsFilter(it, predicate) }
        else -> false
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
        val EXCLUSION_DIRECTIVE = Regex(
            """^\s*(?:exclude|excluding|without|do\s+not\s+show|don't\s+show)\s+""",
            RegexOption.IGNORE_CASE,
        )
        val REMOVE_DIRECTIVE = Regex(
            """\b(?:remove|clear|reset|include\s+again)\b""",
            RegexOption.IGNORE_CASE,
        )
        val EXCLUSION_WORDS = setOf("exclude", "excluding", "without", "not", "show")
    }
}
