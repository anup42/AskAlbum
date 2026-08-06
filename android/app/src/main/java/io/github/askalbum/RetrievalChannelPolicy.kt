package io.github.anup42.askalbum

import java.util.Locale

internal object SemanticQueryVariants {
    private const val MAX_VARIANTS = 8

    fun from(plan: GalleryQueryPlan): List<String> {
        if (plan.intent in setOf(QueryIntent.SUM, QueryIntent.MIN_MAX) && plan.semanticClauses.isEmpty()) return emptyList()
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
        if (eligibleCount <= 0) return notRequired()
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
            if (indexedEligibleIds.isEmpty()) {
                return RetrievalChannelReport(
                    channel = RetrievalChannel.SEMANTIC,
                    status = ChannelStatus.PARTIAL,
                    eligibleCount = eligibleCount,
                    indexedCount = 0,
                    searchedCount = 0,
                    hits = emptyList(),
                    modelVersion = modelVersion,
                    errorCode = "VECTOR_COVERAGE_PARTIAL",
                )
            }
            val hits = search(query, topK, eligibleVectorIds)
            // Video searches include the parent media vector plus zero or more
            // keyframe vectors. Those extra vector entries must not make a
            // fully indexed media scope look partial, while a vector set
            // smaller than the eligible media scope still proves a gap.
            val completeCoverage = eligibleVectorIds.size >= eligibleCount &&
                indexedEligibleIds.size == eligibleVectorIds.size
            RetrievalChannelReport(
                channel = RetrievalChannel.SEMANTIC,
                status = if (completeCoverage) ChannelStatus.SUCCESS else ChannelStatus.PARTIAL,
                eligibleCount = eligibleCount,
                indexedCount = indexedEligibleIds.size.coerceAtMost(eligibleCount),
                searchedCount = indexedEligibleIds.size.coerceAtMost(eligibleCount),
                hits = hits,
                modelVersion = modelVersion,
                errorCode = if (completeCoverage) null else "VECTOR_COVERAGE_PARTIAL",
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
        completePredicateScan: Boolean = false,
    ): ResultExactness = when {
        !allEligibleIndexed -> ResultExactness.PARTIAL_INDEX
        // A full semantic scan only evaluates its semantic predicate. If bounded
        // visual verification was also needed, the visual predicate was not
        // evaluated across the complete eligible set and cannot be called exact.
        verificationApplied -> ResultExactness.ESTIMATED_FROM_RETRIEVAL
        completePredicateScan && semanticReport.status == ChannelStatus.SUCCESS -> ResultExactness.COMPLETE_PREDICATE_SCAN
        deterministicOperation -> ResultExactness.EXACT
        semanticReport.status in setOf(ChannelStatus.UNAVAILABLE, ChannelStatus.FAILED, ChannelStatus.PARTIAL) ->
            ResultExactness.PARTIAL_INDEX
        semanticReport.status != ChannelStatus.NOT_REQUIRED || verificationApplied ->
            ResultExactness.ESTIMATED_FROM_RETRIEVAL
        else -> ResultExactness.ESTIMATED_FROM_RETRIEVAL
    }
}

internal object RetrievalAnswerWording {
    fun countHeadline(count: Int, boundedSemanticRetrieval: Boolean): String = if (boundedSemanticRetrieval) {
        "$count ${if (count == 1) "match" else "matches"} in the current retrieval pass"
    } else {
        "$count matching ${if (count == 1) "item" else "items"}"
    }
}

internal object RetrievalCoverageWording {
    private val boundedChannels = setOf(RetrievalChannel.SEMANTIC, RetrievalChannel.CAPTION_EMBEDDING)

    fun boundedSemanticNoResult(report: RetrievalChannelReport<*>): String {
        val coverage = "indexed coverage ${report.indexedCount} of ${report.eligibleCount} eligible local items"
        return when (report.status) {
            ChannelStatus.UNAVAILABLE ->
                "The semantic channel was unavailable ($coverage), so its required search did not run. " +
                    "No semantic no-result conclusion can be drawn and this is not a complete gallery predicate scan."
            ChannelStatus.FAILED ->
                "The semantic channel failed ($coverage), so its required search did not produce usable results. " +
                    "This is not a complete gallery predicate scan."
            ChannelStatus.PARTIAL ->
                "The semantic channel was partial ($coverage). Its bounded top-K retrieval pass found no supported matches; " +
                    "this is not a complete gallery predicate scan."
            ChannelStatus.SUCCESS ->
                "The semantic channel completed with $coverage. Its bounded top-K retrieval pass found no supported matches; " +
                    "this is not a complete gallery predicate scan."
            ChannelStatus.NOT_REQUIRED ->
                "The semantic channel was not required for this query; no semantic no-result conclusion was drawn."
        }
    }

    fun uiText(report: RetrievalChannelReport<*>): String = if (report.channel in boundedChannels) {
        "indexed ${report.indexedCount}/${report.eligibleCount}; bounded top-K"
    } else {
        "${report.searchedCount}/${report.eligibleCount} searched"
    }
}

/** Applies only high-confidence, metadata-owned negative predicates before ranking. */
internal object DeterministicNegativeClausePolicy {
    private val screenshotPredicate = Regex(
        "\\b(?:screenshots?|screen\\s+shots?|screen\\s+captures?)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun excludes(item: GalleryItem, clauses: Collection<SemanticClause>): Boolean = clauses.any { clause ->
        isDeterministicallyHandled(clause) &&
            containsScreenshot(clause.text) &&
            containsScreenshot(listOf(item.filename, item.title, item.album).plus(item.tags).joinToString(" "))
    }

    fun requiresVisualRejection(clauses: Collection<SemanticClause>): Boolean = clauses.any { clause ->
        clause.polarity == Polarity.NEGATIVE && !isDeterministicallyHandled(clause)
    }

    private fun isDeterministicallyHandled(clause: SemanticClause): Boolean {
        val normalized = SemanticPolarityNormalizer.normalize(clause)
        return normalized.polarity == Polarity.NEGATIVE &&
            normalized.hardness == ConstraintStrength.HARD &&
            containsScreenshot(normalized.text)
    }

    private fun containsScreenshot(text: String): Boolean = screenshotPredicate.containsMatchIn(
        text.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " "),
    )
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
