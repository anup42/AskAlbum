package io.github.anup42.askalbum

import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class CapabilityDescriptor(
    val intent: QueryIntent,
    val executorId: String,
    val suggestedQuery: String,
)

object CapabilityRegistry {
    val descriptors: List<CapabilityDescriptor> = listOf(
        CapabilityDescriptor(QueryIntent.FIND_MEDIA, "hybrid_media", "Show beach sunset photos."),
        CapabilityDescriptor(QueryIntent.LIST, "deterministic_list", "List places in my recent photos."),
        CapabilityDescriptor(QueryIntent.COUNT, "deterministic_or_estimated_count", "How many photos did I take in 2024?"),
        CapabilityDescriptor(QueryIntent.ANSWER_FACT, "ocr_fact", "What is the Wi-Fi password in the latest screenshot?"),
        CapabilityDescriptor(QueryIntent.DOCUMENT_QA, "ocr_document_qa", "What details are in my latest boarding pass document?"),
        CapabilityDescriptor(QueryIntent.SUM, "numeric_sum", "Sum the totals on my Swiggy receipts."),
        CapabilityDescriptor(QueryIntent.MIN_MAX, "numeric_min_max", "Which receipt has the highest total?"),
        CapabilityDescriptor(QueryIntent.EVENT_SUMMARY, "event_summary", "Summarize my latest trip."),
        CapabilityDescriptor(QueryIntent.TIMELINE, "deterministic_timeline", "Show a timeline of my Singapore trip."),
        CapabilityDescriptor(QueryIntent.COMPARE, "deterministic_compare", "Compare my Goa and Singapore trips."),
    )
    private val byIntent = descriptors.associateBy(CapabilityDescriptor::intent)

    val suggestedQueries: List<String> get() = descriptors.map(CapabilityDescriptor::suggestedQuery)
    fun plannerIntentNames(): String = descriptors.joinToString(",") { it.intent.name }
    fun supports(intent: QueryIntent): Boolean = intent in byIntent
    fun requireExecutable(intent: QueryIntent): CapabilityDescriptor =
        requireNotNull(byIntent[intent]) { "No executor is registered for $intent" }
}

data class OcrFactField(
    val key: String,
    val type: OcrEntityType,
    val sourceField: String,
    val aliases: Set<String>,
    val numeric: Boolean = false,
    val sensitive: Boolean = false,
)

object OcrFactAllowlist {
    val fields = listOf(
        OcrFactField("total", OcrEntityType.RECEIPT_TOTAL, "document_total", setOf("total", "receipt_total", "amount_paid"), numeric = true, sensitive = true),
        OcrFactField("amount", OcrEntityType.AMOUNT, "document_amount", setOf("amount", "line_amount", "item_amount", "amount_due", "amount_charged", "amount_payable"), numeric = true, sensitive = true),
        OcrFactField("password", OcrEntityType.PASSWORD, "document_password", setOf("password", "wifi_password", "passcode"), sensitive = true),
        OcrFactField("flight_number", OcrEntityType.FLIGHT_NUMBER, "document_flight_number", setOf("flight", "flight_number")),
        OcrFactField("flight_time", OcrEntityType.FLIGHT_TIME, "document_flight_time", setOf("flight_time", "departure_time", "boarding_time")),
        OcrFactField("order_id", OcrEntityType.ORDER_ID, "document_order_id", setOf("order", "order_id", "booking_id"), sensitive = true),
        OcrFactField("email", OcrEntityType.EMAIL, "document_email", setOf("email", "email_address"), sensitive = true),
        OcrFactField("phone", OcrEntityType.PHONE, "document_phone", setOf("phone", "phone_number", "mobile"), sensitive = true),
        OcrFactField("date", OcrEntityType.DATE, "document_date", setOf("date")),
        OcrFactField("url", OcrEntityType.URL, "document_url", setOf("url", "website", "link")),
        OcrFactField("merchant", OcrEntityType.MERCHANT, "document_merchant", setOf("merchant", "store", "restaurant")),
    )
    private val byAlias = fields.flatMap { field -> field.aliases.map { it to field } }.toMap()
    private val bySource = fields.associateBy(OcrFactField::sourceField)
    private val byCanonicalName = fields.flatMap { field ->
        listOf(field.key, field.sourceField).map { it.lowercase(Locale.ROOT) to field }
    }.toMap()

    fun resolve(value: String?): OcrFactField? = value?.trim()?.lowercase(Locale.ROOT)
        ?.replace(Regex("[^\\p{L}\\p{N}]+"), "_")
        ?.let { normalized -> byAlias[normalized] ?: byCanonicalName[normalized] }

    fun fromSource(sourceField: String): OcrFactField? = bySource[sourceField]
}

data class CapabilityAnswerContext(
    val plan: GalleryQueryPlan,
    val hits: List<SearchHit>,
    val matchCount: Int,
    val exactness: ResultExactness,
    val indexedEligibleCount: Int,
    val totalEligibleCount: Int,
    val warnings: List<String>,
    val channelReports: List<RetrievalChannelReport<SearchHit>>,
    val eventsByMedia: Map<String, EventRecord> = emptyMap(),
    val deterministicHits: List<SearchHit> = emptyList(),
    val comparisonScopes: List<String> = emptyList(),
    val peopleByMedia: Map<String, List<IndexedPersonMetadata>> = emptyMap(),
    val eventCoverageComplete: Boolean = false,
    /** Internal post-auth rendering only. Callers must never expose this context before device authentication. */
    val sensitiveContentAuthorized: Boolean = false,
)

object CapabilityAnswerExecutor {
    private fun collectEvidenceIds(
        hits: List<SearchHit>,
        plan: GalleryQueryPlan,
        limit: Int = 24,
    ): List<String> =
        hits.asSequence()
            .flatMap { hit ->
                hit.evidence.asSequence().filter {
                    it.mediaId == hit.item.id && GroundedEvidencePolicy.allow(it, plan)
                }
            }
            .distinctBy(EvidenceRecord::id)
            .sortedWith(
                compareBy<EvidenceRecord> { GroundedEvidencePolicy.evidencePriority(it) }
                    .thenByDescending { it.confidence },
            )
            .map(EvidenceRecord::id)
            .take(limit)
            .toList()

    fun execute(context: CapabilityAnswerContext): SearchAnswer {
        CapabilityRegistry.requireExecutable(context.plan.intent)
        val evidenceIds = collectEvidenceIds(context.hits + context.deterministicHits, context.plan)
        val base = { headline: String, detail: String, ids: List<String> ->
            SearchAnswer(
                headline,
                detail,
                ids,
                context.exactness,
                context.indexedEligibleCount,
                context.totalEligibleCount,
                warnings = context.warnings,
                channelReports = context.channelReports,
            )
        }
        if (!context.sensitiveContentAuthorized && answerRequiresAuthentication(context)) {
            return SensitiveEvidencePolicy.lock(
                base(
                    SensitiveEvidencePolicy.LOCKED_HEADLINE,
                    SensitiveEvidencePolicy.LOCKED_DETAIL,
                    emptyList(),
                ),
            )
        }
        val answer = when (context.plan.intent) {
            QueryIntent.FIND_MEDIA -> base(
                if (context.exactness == ResultExactness.EXACT ||
                    context.exactness == ResultExactness.COMPLETE_PREDICATE_SCAN
                ) {
                    "${context.hits.size} matching ${if (context.hits.size == 1) "item" else "items"}"
                } else {
                    "${context.hits.size} ${if (context.hits.size == 1) "likely match" else "likely matches"} in this retrieval pass"
                },
                "Hybrid local ranking used only the eligible media scope and the retrieval channels shown below.",
                evidenceIds,
            )
            QueryIntent.LIST -> listAnswer(context, base)
            QueryIntent.COUNT -> base(
                RetrievalAnswerWording.countHeadline(
                    context.matchCount,
                    context.exactness != ResultExactness.EXACT &&
                        context.exactness != ResultExactness.COMPLETE_PREDICATE_SCAN,
                ),
                if (context.exactness == ResultExactness.COMPLETE_PREDICATE_SCAN) {
                    "An exhaustive local semantic predicate scan evaluated every eligible indexed item."
                } else if (context.exactness == ResultExactness.EXACT) {
                    "This is a deterministic count over complete eligible coverage."
                } else {
                    "This is not an exhaustive visual predicate count; channel coverage is shown below."
                },
                evidenceIds,
            )
            QueryIntent.ANSWER_FACT -> factAnswer(context, base)
            QueryIntent.DOCUMENT_QA -> when {
                context.plan.ocrClause?.requestedField.isNullOrBlank() -> documentDetailsAnswer(context, base)
                OcrFactAllowlist.resolve(context.plan.ocrClause?.requestedField) == null && context.hits.isEmpty() -> base(
                    "No supported matches found",
                    "No eligible document matched the requested local constraints.",
                    emptyList(),
                )
                else -> factAnswer(context, base)
            }
            QueryIntent.SUM -> sumAnswer(context, base)
            QueryIntent.MIN_MAX -> minMaxAnswer(context, base)
            QueryIntent.EVENT_SUMMARY -> eventSummary(context, base)
            QueryIntent.TIMELINE -> timeline(context, base)
            QueryIntent.COMPARE -> compare(context, base)
        }
        return answer
    }

    private fun answerRequiresAuthentication(context: CapabilityAnswerContext): Boolean {
        val requestedField = when (context.plan.intent) {
            QueryIntent.LIST,
            QueryIntent.ANSWER_FACT,
            QueryIntent.DOCUMENT_QA,
            -> context.plan.ocrClause?.requestedField
            QueryIntent.SUM,
            QueryIntent.MIN_MAX,
            -> context.plan.aggregation?.field
            else -> null
        }
        val evidence = (context.hits + context.deterministicHits)
            .asSequence()
            .flatMap(SearchHit::evidence)
        val genericDocumentDetails = context.plan.intent == QueryIntent.DOCUMENT_QA && requestedField.isNullOrBlank()
        if (genericDocumentDetails) {
            return evidence.any { item ->
                OcrFactAllowlist.fromSource(item.sourceField) != null &&
                    SensitiveEvidencePolicy.requiresAuthentication(item)
            }
        }
        val requested = OcrFactAllowlist.resolve(requestedField)?.takeIf(OcrFactField::sensitive) ?: return false
        val requiresCompleteCoverageBeforeRendering = context.plan.intent in setOf(
            QueryIntent.ANSWER_FACT,
            QueryIntent.DOCUMENT_QA,
            QueryIntent.SUM,
            QueryIntent.MIN_MAX,
        )
        if (requiresCompleteCoverageBeforeRendering && !context.hasCompleteEligibleCoverage()) return false
        return evidence.any { item ->
            item.sourceField == requested.sourceField && SensitiveEvidencePolicy.requiresAuthentication(item)
        }
    }

    private fun listAnswer(
        context: CapabilityAnswerContext,
        base: (String, String, List<String>) -> SearchAnswer,
    ): SearchAnswer {
        val sourceHits = context.deterministicHits.ifEmpty { context.hits }
        val values = when (context.plan.grouping) {
            Grouping.PLACE -> sourceHits.map { it.item.location }.filter(String::isNotBlank)
            Grouping.PERSON -> sourceHits.flatMap { hit ->
                context.peopleByMedia[hit.item.id].orEmpty().mapNotNull { person ->
                    person.label?.takeIf(String::isNotBlank) ?: person.relationship?.takeIf(String::isNotBlank)
                }
            }
            Grouping.EVENT -> sourceHits.mapNotNull { context.eventsByMedia[it.item.id]?.title }
            Grouping.DAY, Grouping.MONTH, Grouping.YEAR -> sourceHits.mapNotNull { it.item.capturedAt }.map(::formatDate)
            else -> {
                val requested = OcrFactAllowlist.resolve(context.plan.ocrClause?.requestedField)
                if (requested == null) context.hits.map { it.item.title }
                else sourceHits.flatMap(SearchHit::evidence).filter { it.sourceField == requested.sourceField }.map(EvidenceRecord::text)
            }
        }.map(String::trim).filter(String::isNotBlank).distinct()
        val ids = collectEvidenceIds(sourceHits, context.plan)
        val resultNoun = when (context.plan.grouping) {
            Grouping.PLACE -> "place"
            Grouping.PERSON -> "person"
            Grouping.EVENT -> "event"
            Grouping.DAY, Grouping.MONTH, Grouping.YEAR -> "date"
            else -> OcrFactAllowlist.resolve(context.plan.ocrClause?.requestedField)
                ?.key
                ?.replace('_', ' ')
                ?: "result"
        }
        val displayNoun = when {
            values.size == 1 -> resultNoun
            resultNoun == "person" -> "people"
            else -> "${resultNoun}s"
        }
        return base(
            "${values.size} distinct $displayNoun",
            values.take(20).joinToString("; ").ifBlank { "No allowlisted value was present in the eligible evidence." },
            ids,
        )
    }

    private fun factAnswer(
        context: CapabilityAnswerContext,
        base: (String, String, List<String>) -> SearchAnswer,
    ): SearchAnswer {
        val field = OcrFactAllowlist.resolve(context.plan.ocrClause?.requestedField)
            ?: return base(
                "Unsupported document field",
                "The requested document field is not in the local allowlist, so no value was selected.",
                emptyList(),
            )
        if (!context.hasCompleteEligibleCoverage()) {
            return base(
                "Document fact unavailable",
                "The current retrieval pass covered ${context.indexedEligibleCount} of ${context.totalEligibleCount} eligible items. " +
                    "A partial OCR pass cannot establish a trustworthy ${field.key.replace('_', ' ')} value.",
                collectEvidenceIds(context.hits + context.deterministicHits, context.plan, 12),
            )
        }
        val sourceHits = context.deterministicHits.ifEmpty { context.hits }
        val selection = DocumentAnswerSelector.select(sourceHits, setOf(field.sourceField), context.plan.sort)
        val fact = selection?.fact
        return if (fact == null) {
            base(
                "I found the document, but not a reliable ${field.key.replace('_', ' ')}",
                "Open the OCR evidence to inspect the local document. The app will not invent the requested value.",
                collectEvidenceIds(sourceHits, context.plan, 12),
            )
        } else {
            base(
                fact.text,
                "The ${field.key.replace('_', ' ')} comes from allowlisted local OCR in ${selection.document.item.title}.",
                listOf(fact.id),
            )
        }
    }

    private fun documentDetailsAnswer(
        context: CapabilityAnswerContext,
        base: (String, String, List<String>) -> SearchAnswer,
    ): SearchAnswer {
        if (!context.hasCompleteEligibleCoverage()) {
            return base(
                "Document details unavailable",
                "The current retrieval pass covered ${context.indexedEligibleCount} of ${context.totalEligibleCount} eligible items. " +
                    "A partial OCR pass cannot establish a trustworthy document summary.",
                emptyList(),
            )
        }
        val sourceHits = context.deterministicHits.ifEmpty { context.hits }
        if (sourceHits.isEmpty()) {
            return base(
                "No supported matches found",
                "No eligible document matched the requested local constraints.",
                emptyList(),
            )
        }
        val allowedSources = OcrFactAllowlist.fields.mapTo(linkedSetOf(), OcrFactField::sourceField)
        val selectedDocument = DocumentAnswerSelector.select(sourceHits, allowedSources, context.plan.sort)?.document
            ?: sourceHits.first()
        val facts = selectedDocument.evidence
            .asSequence()
            .filter { evidence -> evidence.mediaId == selectedDocument.item.id }
            .mapNotNull { evidence -> OcrFactAllowlist.fromSource(evidence.sourceField)?.let { it to evidence } }
            .groupBy { it.first.sourceField }
            .mapNotNull { (_, candidates) -> candidates.maxByOrNull { it.second.confidence } }
            .sortedBy { (field, _) -> OcrFactAllowlist.fields.indexOf(field) }
        if (facts.isEmpty()) {
            return base(
                "No reliable document details found",
                "The document matched, but no allowlisted structured OCR facts were available. Open its OCR evidence to inspect it locally.",
                collectEvidenceIds(listOf(selectedDocument), context.plan, 12),
            )
        }
        val details = facts.joinToString("; ") { (field, evidence) ->
            "${field.key.replace('_', ' ')}: ${evidence.text}"
        }
        return base(
            "${facts.size} document ${if (facts.size == 1) "detail" else "details"}",
            "From ${selectedDocument.item.title}: $details",
            facts.map { it.second.id },
        )
    }

    private fun sumAnswer(
        context: CapabilityAnswerContext,
        base: (String, String, List<String>) -> SearchAnswer,
    ): SearchAnswer {
        val field = OcrFactAllowlist.resolve(context.plan.aggregation?.field)
            ?: return base(
                "Unsupported document field",
                "The requested numeric field is not in the local allowlist, so no sum was computed.",
                emptyList(),
            )
        if (!field.numeric) return base("Cannot sum ${field.key}", "Only allowlisted numeric document facts can be summed.", emptyList())
        if (!context.hasCompleteEligibleCoverage()) {
            return base(
                "Exact sum unavailable",
                "The current retrieval pass covered ${context.indexedEligibleCount} of ${context.totalEligibleCount} eligible items. " +
                    "A partial pass cannot produce a trustworthy total.",
                collectEvidenceIds(context.hits + context.deterministicHits, context.plan),
            )
        }
        val values = numericFacts(context.deterministicHits.ifEmpty { context.hits }, field)
        if (values.isEmpty()) return base("No compatible numeric facts", "No reliable ${field.key} values were available.", emptyList())
        val currencies = values.map(ParsedFact::currency).distinct()
        if (currencies.size > 1) {
            return base(
                "Mixed currencies were not summed",
                currencies.joinToString("; ") { currency -> "$currency: ${values.count { it.currency == currency }} document(s)" },
                values.map { it.evidence.id },
            )
        }
        val sum = values.fold(BigDecimal.ZERO) { total, value -> total + value.value }
        return base(
            "${currencies.single()} ${sum.stripTrailingZeros().toPlainString()}",
            "Deterministic sum across ${values.size} distinct documents; Gemma arithmetic was not used.",
            values.map { it.evidence.id },
        )
    }

    private fun minMaxAnswer(
        context: CapabilityAnswerContext,
        base: (String, String, List<String>) -> SearchAnswer,
    ): SearchAnswer {
        val field = OcrFactAllowlist.resolve(context.plan.aggregation?.field)
            ?: return base(
                "Unsupported document field",
                "The requested numeric field is not in the local allowlist, so no minimum or maximum was computed.",
                emptyList(),
            )
        if (!context.hasCompleteEligibleCoverage()) {
            return base(
                "Exact minimum or maximum unavailable",
                "The current retrieval pass covered ${context.indexedEligibleCount} of ${context.totalEligibleCount} eligible items. " +
                    "A partial pass cannot establish a trustworthy minimum or maximum.",
                collectEvidenceIds(context.hits + context.deterministicHits, context.plan),
            )
        }
        val values = numericFacts(context.deterministicHits.ifEmpty { context.hits }, field)
        if (values.isEmpty()) return base("No compatible numeric facts", "No reliable ${field.key} values were available.", emptyList())
        if (values.map(ParsedFact::currency).distinct().size > 1) {
            return base("Mixed currencies cannot be compared", "Filter to one currency before requesting a minimum or maximum.", values.map { it.evidence.id })
        }
        val minimum = values.minBy(ParsedFact::value)
        val maximum = values.maxBy(ParsedFact::value)
        val operation = context.plan.aggregation?.operation ?: AggregationOperation.MIN_MAX
        val comparisonEvidenceIds = values.map { it.evidence.id }.distinct()
        val selected = when (operation) {
            AggregationOperation.MIN -> minimum
            AggregationOperation.MAX -> maximum
            else -> null
        }
        if (selected != null) {
            return base(
                "${selected.currency} ${selected.value.stripTrailingZeros().toPlainString()}",
                if (operation == AggregationOperation.MIN) {
                    "Minimum: ${selected.hit.item.title}."
                } else {
                    "Maximum: ${selected.hit.item.title}."
                },
                (listOf(selected.evidence.id) + comparisonEvidenceIds).distinct().take(24),
            )
        }
        return base(
            "Min ${minimum.currency} ${minimum.value.stripTrailingZeros().toPlainString()}; max ${maximum.currency} ${maximum.value.stripTrailingZeros().toPlainString()}",
            "Minimum: ${minimum.hit.item.title}. Maximum: ${maximum.hit.item.title}.",
            listOf(minimum.evidence.id, maximum.evidence.id).distinct(),
        )
    }

    private fun eventSummary(
        context: CapabilityAnswerContext,
        base: (String, String, List<String>) -> SearchAnswer,
    ): SearchAnswer {
        val sourceHits = context.deterministicHits.ifEmpty { context.hits }
        val completeCoverage = context.eventCoverageComplete
        val events = sourceHits.mapNotNull { context.eventsByMedia[it.item.id] }.distinctBy(EventRecord::id)
        val captures = sourceHits.mapNotNull { it.item.capturedAt }
        val range = if (captures.isEmpty()) "date unavailable" else "${formatDate(captures.min())} to ${formatDate(captures.max())}"
        val places = sourceHits.map { it.item.location }.filter(String::isNotBlank).distinct().take(4)
        val people = sourceHits
            .asSequence()
            .flatMap { hit -> context.peopleByMedia[hit.item.id].orEmpty().asSequence() }
            .filter { person -> person.reviewed && !person.hidden }
            .mapNotNull { person ->
                person.label?.trim()?.takeIf(String::isNotBlank)
                    ?: person.relationship?.trim()?.takeIf(String::isNotBlank)
            }
            .distinctBy { person -> person.lowercase(java.util.Locale.ROOT) }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .toList()
        return base(
            events.firstOrNull()?.title ?: "Event summary",
            "Date range: $range. Places: ${places.joinToString().ifBlank { "not recorded" }}. " +
                "Reviewed people: ${people.joinToString().ifBlank { "none identified in this event" }}. " +
                "Representative media: ${sourceHits.take(4).joinToString { it.item.title }}. " +
                if (completeCoverage) {
                    "The matched event membership was evaluated completely over eligible local media."
                } else {
                    "This summary uses the current ranked retrieval pass and may not include every event member."
                },
            collectEvidenceIds(sourceHits, context.plan),
        )
    }

    private fun timeline(
        context: CapabilityAnswerContext,
        base: (String, String, List<String>) -> SearchAnswer,
    ): SearchAnswer {
        val sourceHits = context.deterministicHits.ifEmpty { context.hits }
        val completeCoverage = context.eventCoverageComplete
        val buckets = sourceHits.filter { it.item.capturedAt != null }
            .groupBy { formatDate(requireNotNull(it.item.capturedAt)) }
            .toSortedMap()
        return base(
            "${buckets.size} chronological ${if (buckets.size == 1) "date" else "dates"}",
            buckets.entries.take(20).joinToString("; ") { (date, hits) -> "$date: ${hits.size}" }
                .ifBlank { "No deterministic capture dates were available." } +
                if (completeCoverage) {
                    " Complete dates are shown for the resolved event scope."
                } else {
                    " Dates are limited to the current retrieval pass."
                },
            collectEvidenceIds(sourceHits, context.plan),
        )
    }

    private fun compare(
        context: CapabilityAnswerContext,
        base: (String, String, List<String>) -> SearchAnswer,
    ): SearchAnswer {
        if (context.comparisonScopes.size >= 2) return compareExplicitScopes(context, base)
        val sourceHits = context.deterministicHits.ifEmpty { context.hits }
        val grouped = sourceHits.groupBy { hit ->
            when (context.plan.grouping) {
                Grouping.EVENT -> context.eventsByMedia[hit.item.id]?.title
                Grouping.PLACE -> hit.item.location
                else -> hit.item.album.ifBlank { hit.item.location }
            }.orEmpty().ifBlank { hit.item.title }
        }.entries.sortedByDescending { it.value.size }.take(2)
        if (grouped.size < 2) return base("Two comparison scopes were not resolved", "Refine the query with two places, events, albums, or result sets.", emptyList())
        val detail = grouped.joinToString(" | ") { (name, hits) ->
            val captures = hits.mapNotNull { it.item.capturedAt }
            "$name: ${hits.size} item(s), ${captures.minOrNull()?.let(::formatDate) ?: "date unavailable"} to " +
                (captures.maxOrNull()?.let(::formatDate) ?: "date unavailable")
        }
        return base(
            "${grouped[0].key} compared with ${grouped[1].key}",
            detail + if (context.hasCompleteEligibleCoverage()) {
                " Complete eligible membership was used for the resolved comparison scopes."
            } else {
                " Comparison is based on the current ranked retrieval pass."
            },
            collectEvidenceIds(grouped.flatMap { it.value }, context.plan),
        )
    }

    private fun compareExplicitScopes(
        context: CapabilityAnswerContext,
        base: (String, String, List<String>) -> SearchAnswer,
    ): SearchAnswer {
        val sourceHits = context.deterministicHits.ifEmpty { context.hits }
        val grouped = context.comparisonScopes.mapNotNull { scope ->
            val needle = scope.trim().lowercase(Locale.ROOT).takeIf(String::isNotBlank) ?: return@mapNotNull null
            val scopedHits = sourceHits.filter { hit ->
                listOf(
                    hit.item.location,
                    hit.item.album,
                    hit.item.title,
                    hit.item.filename,
                    hit.item.description,
                    context.eventsByMedia[hit.item.id]?.title.orEmpty(),
                    context.eventsByMedia[hit.item.id]?.locationName.orEmpty(),
                    context.eventsByMedia[hit.item.id]?.searchText.orEmpty(),
                ).plus(hit.item.tags).any { needle in it.lowercase(Locale.ROOT) }
            }.distinctBy { it.item.id }
            scope.trim().replaceFirstChar { it.uppercase(Locale.ROOT) } to scopedHits
        }.filter { it.second.isNotEmpty() }.take(2)
        if (grouped.size < 2) {
            return base(
                "Two comparison scopes were not resolved",
                "Refine the query with two places, events, albums, or result sets.",
                emptyList(),
            )
        }
        val detail = grouped.joinToString("; ") { (name, hits) ->
            val captures = hits.mapNotNull { it.item.capturedAt }
            "$name: ${hits.size} item(s), ${captures.minOrNull()?.let(::formatDate) ?: "date unavailable"} to " +
                (captures.maxOrNull()?.let(::formatDate) ?: "date unavailable")
        }
        return base(
            "${grouped[0].first} compared with ${grouped[1].first}",
            detail + if (context.hasCompleteEligibleCoverage()) {
                " Complete eligible membership was used for the resolved comparison scopes."
            } else {
                " Comparison is based on the current ranked retrieval pass."
            },
            collectEvidenceIds(grouped.flatMap { it.second }, context.plan),
        )
    }

    private data class ParsedFact(
        val hit: SearchHit,
        val evidence: EvidenceRecord,
        val value: BigDecimal,
        val currency: String,
    )

    private fun numericFacts(hits: List<SearchHit>, field: OcrFactField): List<ParsedFact> = hits
        .distinctBy(::documentIdentity)
        .mapNotNull { hit ->
        val evidence = hit.evidence.firstOrNull { it.sourceField == field.sourceField } ?: return@mapNotNull null
        val number = NUMBER.find(evidence.text)?.value?.replace(",", "")?.let { runCatching { BigDecimal(it) }.getOrNull() }
            ?: return@mapNotNull null
        ParsedFact(hit, evidence, number, currency(evidence.text))
    }

    /** Exact content digests identify the same document across duplicate media rows. */
    private fun documentIdentity(hit: SearchHit): String =
        hit.item.exactContentDigest?.trim()?.takeIf(String::isNotBlank)?.let { "digest:$it" }
            ?: "media:${hit.item.id}"

    private fun currency(text: String): String = when {
        Regex("(?i)(\\u20B9|\\binr\\b|\\brs\\.?)").containsMatchIn(text) -> "INR"
        Regex("(?i)(\\busd\\b|\\$)").containsMatchIn(text) -> "USD"
        else -> "UNSPECIFIED"
    }

    private fun CapabilityAnswerContext.hasCompleteEligibleCoverage(): Boolean =
        exactness == ResultExactness.EXACT || exactness == ResultExactness.COMPLETE_PREDICATE_SCAN

    private fun formatDate(epochMs: Long): String = DATE_FORMAT.format(Instant.ofEpochMilli(epochMs))

    private val NUMBER = Regex("[-+]?\\d[\\d,]*(?:\\.\\d+)?")
    private val DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault())
}
