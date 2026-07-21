package com.askphotos.android

data class DeterministicPlanOverlayResult(
    val plan: GalleryQueryPlan,
    val applied: Boolean,
)

/** Keeps exact dates, media types, and document fields in Kotlin even when Gemma supplies the semantics. */
class DeterministicPlanOverlay(
    private val compiler: QueryCompiler = QueryCompiler(),
    private val validator: GalleryQueryPlanValidator = GalleryQueryPlanValidator(),
) {
    fun apply(
        query: String,
        modelPlan: GalleryQueryPlan,
        activeResultIds: Set<String>?,
    ): DeterministicPlanOverlayResult {
        val deterministic = compiler.compile(query, activeResultIds)
        val qualityOnlyFollowUp = deterministic.baseResultIds != null &&
            deterministic.sort == SortSpec.QUALITY && deterministic.terms.isEmpty()
        val filterOnlyFollowUp = deterministic.baseResultIds != null &&
            deterministic.filter != FilterExpression.True && deterministic.terms.isEmpty()
        val merged = modelPlan.copy(
            intent = if (deterministic.intent != QueryIntent.FIND_MEDIA) deterministic.intent else modelPlan.intent,
            mediaScope = if (deterministic.mediaScope != MediaScope.ALL) deterministic.mediaScope else modelPlan.mediaScope,
            filter = mergeFilter(modelPlan.filter, deterministic.filter),
            ocrClause = mergeOcrClause(modelPlan.ocrClause, deterministic.ocrClause),
            grouping = if (modelPlan.grouping == Grouping.NONE) deterministic.grouping else modelPlan.grouping,
            sort = if (deterministic.sort != SortSpec.RELEVANCE) deterministic.sort else modelPlan.sort,
            semanticClauses = if (qualityOnlyFollowUp || filterOnlyFollowUp) emptyList() else modelPlan.semanticClauses,
            terms = if (qualityOnlyFollowUp || filterOnlyFollowUp) emptyList() else modelPlan.terms,
            place = deterministic.place ?: modelPlan.place,
            baseResultIds = modelPlan.baseResultIds ?: deterministic.baseResultIds,
        )
        return DeterministicPlanOverlayResult(
            plan = validator.requireValid(merged, activeResultIds),
            applied = merged != modelPlan,
        )
    }

    private fun mergeFilter(model: FilterExpression, deterministic: FilterExpression): FilterExpression = when {
        deterministic == FilterExpression.True || deterministic == model -> model
        model == FilterExpression.True -> deterministic
        model is FilterExpression.And && deterministic in model.clauses -> model
        model is FilterExpression.And -> FilterExpression.And(model.clauses + deterministic)
        else -> FilterExpression.And(listOf(model, deterministic))
    }

    private fun mergeOcrClause(model: OcrClause?, deterministic: OcrClause?): OcrClause? = when {
        model == null -> deterministic
        deterministic == null -> model
        else -> model.copy(
            query = model.query ?: deterministic.query,
            merchant = model.merchant ?: deterministic.merchant,
            requestedField = model.requestedField ?: deterministic.requestedField,
        )
    }
}
