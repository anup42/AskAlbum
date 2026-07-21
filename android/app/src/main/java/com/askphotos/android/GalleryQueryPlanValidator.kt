package com.askphotos.android

data class PlanValidationResult(val errors: List<String>) {
    val isValid: Boolean get() = errors.isEmpty()
}

/** Rejects unsafe or unsupported model output before any executor sees it. */
class GalleryQueryPlanValidator(
    private val supportedVersion: Int = 1,
    private val maxLimit: Int = 100,
) {
    private val unsafeText = Regex(
        "(?i)(content://|file://|/storage/|\\\\|;|--|\\b(select|insert|update|delete|drop|alter|pragma)\\b)",
    )

    fun validate(
        plan: GalleryQueryPlan,
        activeResultIds: Set<String>? = null,
    ): PlanValidationResult {
        val errors = mutableListOf<String>()
        if (plan.version != supportedVersion) errors += "Unsupported plan version"
        if (plan.limit !in 1..maxLimit) errors += "Candidate limit must be between 1 and $maxLimit"
        if (plan.originalQuery.isBlank() || plan.originalQuery.length > 2_000) errors += "Invalid original query"
        if (plan.terms.size > 16) errors += "Too many retrieval terms"
        plan.terms.forEach { validateText("term", it, errors, 80) }
        plan.place?.let { validateText("place", it, errors, 120) }
        if (plan.semanticClauses.size > 16) errors += "Too many semantic clauses"
        plan.semanticClauses.forEach {
            validateText("semantic clause", it.text, errors, 240)
            it.canonicalText?.let { text -> validateText("canonical clause", text, errors, 240) }
            it.relationToPerson?.let { person -> validateIdentifier("person relation", person, errors) }
        }
        if (plan.peopleClauses.size > 8) errors += "Too many people clauses"
        plan.peopleClauses.forEach { validateIdentifier("person", it.personId, errors) }
        plan.ocrClause?.let {
            it.query?.let { text -> validateText("OCR query", text, errors, 240) }
            it.merchant?.let { text -> validateText("merchant", text, errors, 120) }
            it.requestedField?.let { field -> validateIdentifier("OCR field", field, errors) }
        }
        validateFilter(plan.filter, errors, 0)
        validateContradictions(plan, errors)
        validateAggregation(plan, errors)
        validateFollowUp(plan, activeResultIds, errors)
        return PlanValidationResult(errors.distinct())
    }

    fun requireValid(plan: GalleryQueryPlan, activeResultIds: Set<String>? = null): GalleryQueryPlan {
        val result = validate(plan, activeResultIds)
        require(result.isValid) { result.errors.joinToString("; ") }
        return plan
    }

    private fun validateText(label: String, value: String, errors: MutableList<String>, maxLength: Int) {
        if (value.isBlank() || value.length > maxLength) errors += "Invalid $label"
        if (unsafeText.containsMatchIn(value)) errors += "Unsafe $label"
    }

    private fun validateIdentifier(label: String, value: String, errors: MutableList<String>) {
        if (!value.matches(Regex("[A-Za-z0-9_-]{1,80}"))) errors += "Invalid $label reference"
    }

    private fun validateFilter(filter: FilterExpression, errors: MutableList<String>, depth: Int) {
        if (depth > 4) {
            errors += "Filter expression is too deeply nested"
            return
        }
        when (filter) {
            FilterExpression.True -> Unit
            is FilterExpression.And -> {
                if (filter.clauses.isEmpty() || filter.clauses.size > 12) errors += "Invalid filter clause count"
                filter.clauses.forEach { validateFilter(it, errors, depth + 1) }
            }
            is FilterExpression.TimeRange -> if (
                filter.startEpochMs != null && filter.endEpochMs != null && filter.startEpochMs > filter.endEpochMs
            ) errors += "Invalid time range"
            is FilterExpression.MediaKindIs -> Unit
            is FilterExpression.AlbumIs -> validateText("album", filter.album, errors, 160)
        }
    }

    private fun validateContradictions(plan: GalleryQueryPlan, errors: MutableList<String>) {
        val hardSemantic = plan.semanticClauses.filter { it.hardness == ConstraintStrength.HARD }
        val semanticGroups = hardSemantic.groupBy {
            listOf(it.canonicalText ?: it.text, it.subject.name, it.relationToPerson.orEmpty())
                .joinToString("|").lowercase()
        }
        if (semanticGroups.values.any { clauses -> clauses.map { it.polarity }.distinct().size > 1 }) {
            errors += "Contradictory hard semantic constraints"
        }
        val hardPeople = plan.peopleClauses.filter { it.hardness == ConstraintStrength.HARD }.groupBy { it.personId }
        if (hardPeople.values.any { clauses -> clauses.map { it.mustBePresent }.distinct().size > 1 }) {
            errors += "Contradictory hard people constraints"
        }
    }

    private fun validateAggregation(plan: GalleryQueryPlan, errors: MutableList<String>) {
        val required = when (plan.intent) {
            QueryIntent.COUNT -> AggregationOperation.COUNT
            QueryIntent.SUM -> AggregationOperation.SUM
            QueryIntent.MIN_MAX -> AggregationOperation.MIN_MAX
            else -> null
        }
        if (required != null && plan.aggregation?.operation != required) errors += "Intent requires $required aggregation"
    }

    private fun validateFollowUp(
        plan: GalleryQueryPlan,
        activeResultIds: Set<String>?,
        errors: MutableList<String>,
    ) {
        val base = plan.baseResultIds ?: return
        if (base.isEmpty()) errors += "Follow-up result set is empty"
        if (activeResultIds == null || !activeResultIds.containsAll(base)) errors += "Invalid follow-up result reference"
    }
}
