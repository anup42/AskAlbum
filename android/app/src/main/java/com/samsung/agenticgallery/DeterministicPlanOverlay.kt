package com.samsung.agenticgallery

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
        val deterministicAggregationOnly = deterministic.intent in setOf(QueryIntent.COUNT, QueryIntent.SUM, QueryIntent.MIN_MAX) &&
            deterministic.aggregation != null && deterministic.semanticClauses.isEmpty() && deterministic.terms.isEmpty()
        val merged = modelPlan.copy(
            intent = if (deterministic.intent != QueryIntent.FIND_MEDIA) deterministic.intent else modelPlan.intent,
            mediaScope = if (deterministic.mediaScope != MediaScope.ALL) deterministic.mediaScope else modelPlan.mediaScope,
            filter = mergeFilter(modelPlan.filter, deterministic.filter),
            ocrClause = mergeOcrClause(modelPlan.ocrClause, deterministic.ocrClause),
            grouping = if (modelPlan.grouping == Grouping.NONE) deterministic.grouping else modelPlan.grouping,
            sort = if (deterministic.sort != SortSpec.RELEVANCE) deterministic.sort else modelPlan.sort,
            aggregation = deterministic.aggregation ?: modelPlan.aggregation,
            semanticClauses = if (qualityOnlyFollowUp || filterOnlyFollowUp || deterministicAggregationOnly) emptyList() else modelPlan.semanticClauses,
            terms = if (qualityOnlyFollowUp || filterOnlyFollowUp || deterministicAggregationOnly) emptyList() else modelPlan.terms,
            place = deterministic.place ?: modelPlan.place,
            baseResultIds = modelPlan.baseResultIds ?: deterministic.baseResultIds,
        )
        return DeterministicPlanOverlayResult(
            plan = validator.requireValid(merged, activeResultIds),
            applied = merged != modelPlan,
        )
    }

    private fun mergeFilter(model: FilterExpression, deterministic: FilterExpression): FilterExpression {
        val exact = flattenFilter(deterministic)
        if (exact.isEmpty()) return model
        val exactSlots = exact.mapTo(mutableSetOf(), ::filterSlot)
        val retainedModel = flattenFilter(model).filterNot { filterSlot(it) in exactSlots }
        return composeFilter((retainedModel + exact).distinct())
    }

    private fun flattenFilter(filter: FilterExpression): List<FilterExpression> = when (filter) {
        FilterExpression.True -> emptyList()
        is FilterExpression.And -> filter.clauses.flatMap(::flattenFilter)
        else -> listOf(filter)
    }

    private fun composeFilter(clauses: List<FilterExpression>): FilterExpression = when (clauses.size) {
        0 -> FilterExpression.True
        1 -> clauses.single()
        else -> FilterExpression.And(clauses)
    }

    private fun filterSlot(filter: FilterExpression): String = when (filter) {
        is FilterExpression.TimeRange -> "time"
        is FilterExpression.MediaKindIs -> "media-kind"
        is FilterExpression.AlbumIs -> "album"
        is FilterExpression.And -> error("AND filters must be flattened before slot comparison")
        FilterExpression.True -> error("TRUE filters have no replacement slot")
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
