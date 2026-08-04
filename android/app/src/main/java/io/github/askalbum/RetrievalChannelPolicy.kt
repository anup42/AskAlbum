package io.github.anup42.askalbum

import java.util.Locale

internal object CaptionCoveragePolicy {
    fun status(eligibleCount: Int, indexedCount: Int): ChannelStatus {
        return if (indexedCount >= eligibleCount) ChannelStatus.SUCCESS else ChannelStatus.PARTIAL
    }
}

internal object SemanticQueryVariants {
    private const val MAX_VARIANTS = 8

    fun from(plan: GalleryQueryPlan): List<String> {
        if (plan.terms.isEmpty() && plan.semanticClauses.isEmpty()) return emptyList()
        val executionTerms = RetrievalTerms.forExecution(
            plan.terms,
            reviewedPeopleFilterApplied = plan.peopleClauses.isNotEmpty(),
        )
        return buildList {
            add(plan.originalQuery)
            plan.semanticClauses.asSequence()
                .filter { it.polarity == Polarity.POSITIVE }
                .filter {
                    RetrievalTerms.forExecution(
                        listOf(it.canonicalText ?: it.text),
                        reviewedPeopleFilterApplied = plan.peopleClauses.isNotEmpty(),
                    ).isNotEmpty()
                }
                .forEach { clause ->
                    add(clause.text)
                    clause.canonicalText?.let(::add)
                }
            addAll(RetrievalConceptExpansion.semanticQueries(executionTerms))
        }.map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(MAX_VARIANTS)
    }
}

internal object SemanticChannelReporter {
    suspend fun execute(
        query: String,
        modelVersion: String?,
        eligibleCount: Int,
        eligibleVectorIds: Set<String>,
        topK: Int,
        indexedIds: suspend () -> Set<String>,
        search: suspend (String, Int, Set<String>) -> List<VectorHit>,
    ): RetrievalChannelReport<VectorHit> {
        if (query.isBlank()) return notRequired()
        if (modelVersion == null) {
            return RetrievalChannelReport(
                RetrievalChannel.SEMANTIC,
                ChannelStatus.UNAVAILABLE,
                eligibleCount,
                0,
                0,
                emptyList(),
                errorCode = "RETRIEVAL_PACK_UNAVAILABLE",
            )
        }
        return try {
            val indexedEligibleIds = indexedIds().intersect(eligibleVectorIds)
            val hits = search(query, topK, eligibleVectorIds)
            RetrievalChannelReport(
                channel = RetrievalChannel.SEMANTIC,
                status = if (indexedEligibleIds.size < eligibleCount) ChannelStatus.PARTIAL else ChannelStatus.SUCCESS,
                eligibleCount = eligibleCount,
                indexedCount = indexedEligibleIds.size,
                searchedCount = indexedEligibleIds.size,
                hits = hits,
                modelVersion = modelVersion,
                errorCode = if (indexedEligibleIds.size < eligibleCount) "VECTOR_COVERAGE_PARTIAL" else null,
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            RetrievalChannelReport(
                RetrievalChannel.SEMANTIC,
                ChannelStatus.FAILED,
                eligibleCount,
                0,
                0,
                emptyList(),
                modelVersion,
                "TEXT_EMBEDDING_OR_VECTOR_SEARCH_FAILED",
            )
        }
    }

    fun notRequired(): RetrievalChannelReport<VectorHit> = RetrievalChannelReport(
        RetrievalChannel.SEMANTIC,
        ChannelStatus.NOT_REQUIRED,
        0,
        0,
        0,
        emptyList(),
    )
}

internal object SemanticChannelReportFusion {
    fun fuse(reports: List<RetrievalChannelReport<VectorHit>>): RetrievalChannelReport<VectorHit> {
        if (reports.isEmpty()) return SemanticChannelReporter.notRequired()
        val active = reports.filter { it.status != ChannelStatus.NOT_REQUIRED }
        if (active.isEmpty()) return SemanticChannelReporter.notRequired()
        val usable = active.filter { it.status in setOf(ChannelStatus.SUCCESS, ChannelStatus.PARTIAL) }
        val status = when {
            active.all { it.status == ChannelStatus.SUCCESS } -> ChannelStatus.SUCCESS
            active.all { it.status == ChannelStatus.UNAVAILABLE } -> ChannelStatus.UNAVAILABLE
            active.all { it.status == ChannelStatus.FAILED } -> ChannelStatus.FAILED
            usable.isNotEmpty() -> ChannelStatus.PARTIAL
            else -> ChannelStatus.FAILED
        }
        val ranked = HybridRankFusion.fuse(
            usable.map { report -> RankedChannel(1.0, report.hits.map(VectorHit::mediaId)) },
        )
        val hitById = usable.flatMap { it.hits }.groupBy(VectorHit::mediaId)
            .mapValues { (_, hits) -> hits.maxBy(VectorHit::score) }
        return RetrievalChannelReport(
            channel = RetrievalChannel.SEMANTIC,
            status = status,
            eligibleCount = active.maxOfOrNull { it.eligibleCount } ?: 0,
            indexedCount = active.maxOfOrNull { it.indexedCount } ?: 0,
            searchedCount = active.maxOfOrNull { it.searchedCount } ?: 0,
            hits = ranked.mapNotNull { hitById[it.first] },
            modelVersion = active.mapNotNull { it.modelVersion }.firstOrNull(),
            errorCode = active.mapNotNull { it.errorCode }.distinct().joinToString("+").takeIf(String::isNotBlank),
        )
    }
}

internal object RetrievalExactnessPolicy {
    fun resolve(
        allEligibleIndexed: Boolean,
        deterministicOperation: Boolean,
        semanticReport: RetrievalChannelReport<*>,
        verificationApplied: Boolean,
    ): ResultExactness = when {
        !allEligibleIndexed -> ResultExactness.PARTIAL_INDEX
        semanticReport.status in setOf(ChannelStatus.UNAVAILABLE, ChannelStatus.FAILED, ChannelStatus.PARTIAL) ->
            ResultExactness.PARTIAL_INDEX
        semanticReport.status != ChannelStatus.NOT_REQUIRED || verificationApplied ->
            ResultExactness.ESTIMATED_FROM_RETRIEVAL
        deterministicOperation -> ResultExactness.EXACT
        else -> ResultExactness.COMPLETE_MODEL_SCAN
    }
}

internal object RetrievalAnswerWording {
    fun countHeadline(count: Int, boundedSemanticRetrieval: Boolean): String = if (boundedSemanticRetrieval) {
        "$count ${if (count == 1) "match" else "matches"} in the current retrieval pass"
    } else {
        "$count matching ${if (count == 1) "item" else "items"}"
    }
}

internal fun <T, R> RetrievalChannelReport<T>.mapHits(transform: (T) -> R?): RetrievalChannelReport<R> =
    RetrievalChannelReport(
        channel,
        status,
        eligibleCount,
        indexedCount,
        searchedCount,
        hits.mapNotNull(transform),
        modelVersion,
        errorCode,
    )
