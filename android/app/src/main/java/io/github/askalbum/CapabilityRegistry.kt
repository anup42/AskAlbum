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
        CapabilityDescriptor(QueryIntent.DOCUMENT_QA, "ocr_document_qa", "What is the flight number on my latest ticket?"),
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
        OcrFactField("total", OcrEntityType.RECEIPT_TOTAL, "document_total", setOf("total", "receipt_total", "amount", "amount_paid"), numeric = true),
        OcrFactField("password", OcrEntityType.PASSWORD, "document_password", setOf("password", "wifi_password", "passcode"), sensitive = true),
        OcrFactField("flight_number", OcrEntityType.FLIGHT_NUMBER, "document_flight_number", setOf("flight", "flight_number")),
        OcrFactField("flight_time", OcrEntityType.FLIGHT_TIME, "document_flight_time", setOf("flight_time", "departure_time", "boarding_time")),
        OcrFactField("order_id", OcrEntityType.ORDER_ID, "document_order_id", setOf("order", "order_id", "booking_id")),
        OcrFactField("email", OcrEntityType.EMAIL, "document_email", setOf("email", "email_address")),
        OcrFactField("phone", OcrEntityType.PHONE, "document_phone", setOf("phone", "phone_number", "mobile")),
        OcrFactField("date", OcrEntityType.DATE, "document_date", setOf("date")),
        OcrFactField("url", OcrEntityType.URL, "document_url", setOf("url", "website", "link")),
        OcrFactField("merchant", OcrEntityType.MERCHANT, "document_merchant", setOf("merchant", "store", "restaurant")),
    )
    private val byAlias = fields.flatMap { field -> field.aliases.map { it to field } }.toMap()
    private val bySource = fields.associateBy(OcrFactField::sourceField)

    fun resolve(value: String?): OcrFactField? = value?.trim()?.lowercase(Locale.ROOT)
        ?.replace(Regex("[^\\p{L}\\p{N}]+"), "_")
        ?.let(byAlias::get)

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
)

object CapabilityAnswerExecutor {
    fun execute(context: CapabilityAnswerContext): SearchAnswer {
        CapabilityRegistry.requireExecutable(context.plan.intent)
        val evidenceIds = context.hits.flatMap(SearchHit::evidence).map(EvidenceRecord::id).distinct().take(24)
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
        return when (context.plan.intent) {
            QueryIntent.FIND_MEDIA -> base(
                "Found ${context.hits.size} ${if (context.hits.size == 1) "match" else "matches"}",
                "Hybrid local ranking used only the eligible media scope and the retrieval channels shown below.",
                evidenceIds,
            )
            QueryIntent.LIST -> listAnswer(context, base)
            QueryIntent.COUNT -> base(
                RetrievalAnswerWording.countHeadline(
                    context.matchCount,
                    context.channelReports.any { it.channel == RetrievalChannel.SEMANTIC && it.status != ChannelStatus.NOT_REQUIRED },
                ),
                if (context.exactness == ResultExactness.EXACT) {
                    "This is a deterministic count over complete eligible coverage."
                } else {
                    "This is not an exhaustive visual predicate count; channel coverage is shown below."
                },
                evidenceIds,
            )
            QueryIntent.ANSWER_FACT, QueryIntent.DOCUMENT_QA -> factAnswer(context, base)
            QueryIntent.SUM -> sumAnswer(context, base)
            QueryIntent.MIN_MAX -> minMaxAnswer(context, base)
            QueryIntent.EVENT_SUMMARY -> eventSummary(context, base)
            QueryIntent.TIMELINE -> timeline(context, base)
            QueryIntent.COMPARE -> compare(context, base)
        }
    }

    private fun listAnswer(
        context: CapabilityAnswerContext,
        base: (String, String, List<String>) -> SearchAnswer,
    ): SearchAnswer {
        val values = when (context.plan.grouping) {
            Grouping.PLACE -> context.hits.map { it.item.location }.filter(String::isNotBlank)
            Grouping.EVENT -> context.hits.mapNotNull { context.eventsByMedia[it.item.id]?.title }
            Grouping.DAY, Grouping.MONTH, Grouping.YEAR -> context.hits.mapNotNull { it.item.capturedAt }.map(::formatDate)
            else -> {
                val requested = OcrFactAllowlist.resolve(context.plan.ocrClause?.requestedField)
                if (requested == null) context.hits.map { it.item.title }
                else context.hits.flatMap(SearchHit::evidence).filter { it.sourceField == requested.sourceField }.map(EvidenceRecord::text)
            }
        }.map(String::trim).filter(String::isNotBlank).distinct()
        val ids = context.hits.flatMap(SearchHit::evidence).map(EvidenceRecord::id).distinct().take(24)
        return base(
            "${values.size} distinct ${if (values.size == 1) "result" else "results"}",
            values.take(20).joinToString(" • ").ifBlank { "No allowlisted value was present in the eligible evidence." },
            ids,
        )
    }

    private fun factAnswer(
        context: CapabilityAnswerContext,
        base: (String, String, List<String>) -> SearchAnswer,
    ): SearchAnswer {
        val field = OcrFactAllowlist.resolve(context.plan.ocrClause?.requestedField)
            ?: OcrFactAllowlist.fields.first()
        val selection = DocumentAnswerSelector.select(context.hits, setOf(field.sourceField))
        val fact = selection?.fact
        return if (fact == null) {
            base(
                "I found the document, but not a reliable ${field.key.replace('_', ' ')}",
                "Open the OCR evidence to inspect the local document. The app will not invent the requested value.",
                context.hits.flatMap(SearchHit::evidence).map(EvidenceRecord::id).distinct().take(12),
            )
        } else {
            base(
                fact.text,
                "The ${field.key.replace('_', ' ')} comes from allowlisted local OCR in ${selection.document.item.title}.",
                listOf(fact.id),
            )
        }
    }

    private fun sumAnswer(
        context: CapabilityAnswerContext,
        base: (String, String, List<String>) -> SearchAnswer,
    ): SearchAnswer {
        val field = OcrFactAllowlist.resolve(context.plan.aggregation?.field) ?: OcrFactAllowlist.fields.first()
        if (!field.numeric) return base("Cannot sum ${field.key}", "Only allowlisted numeric document facts can be summed.", emptyList())
        val values = numericFacts(context.hits, field)
        if (values.isEmpty()) return base("No compatible numeric facts", "No reliable ${field.key} values were available.", emptyList())
        val currencies = values.map(ParsedFact::currency).distinct()
        if (currencies.size > 1) {
            return base(
                "Mixed currencies were not summed",
                currencies.joinToString(" • ") { currency -> "$currency: ${values.count { it.currency == currency }} document(s)" },
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
        val field = OcrFactAllowlist.resolve(context.plan.aggregation?.field) ?: OcrFactAllowlist.fields.first()
        val values = numericFacts(context.hits, field)
        if (values.isEmpty()) return base("No compatible numeric facts", "No reliable ${field.key} values were available.", emptyList())
        if (values.map(ParsedFact::currency).distinct().size > 1) {
            return base("Mixed currencies cannot be compared", "Filter to one currency before requesting a minimum or maximum.", values.map { it.evidence.id })
        }
        val minimum = values.minBy(ParsedFact::value)
        val maximum = values.maxBy(ParsedFact::value)
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
        val events = context.hits.mapNotNull { context.eventsByMedia[it.item.id] }.distinctBy(EventRecord::id)
        val captures = context.hits.mapNotNull { it.item.capturedAt }
        val range = if (captures.isEmpty()) "date unavailable" else "${formatDate(captures.min())} to ${formatDate(captures.max())}"
        val places = context.hits.map { it.item.location }.filter(String::isNotBlank).distinct().take(4)
        val people = context.plan.peopleClauses.filter(PersonClause::mustBePresent).map(PersonClause::personId).distinct()
        return base(
            events.firstOrNull()?.title ?: "Event summary",
            "Date range: $range. Places: ${places.joinToString().ifBlank { "not recorded" }}. " +
                "Reviewed people: ${people.joinToString().ifBlank { "none requested" }}. " +
                "Representative media: ${context.hits.take(4).joinToString { it.item.title }}.",
            context.hits.flatMap(SearchHit::evidence).map(EvidenceRecord::id).distinct().take(24),
        )
    }

    private fun timeline(
        context: CapabilityAnswerContext,
        base: (String, String, List<String>) -> SearchAnswer,
    ): SearchAnswer {
        val buckets = context.hits.filter { it.item.capturedAt != null }
            .groupBy { formatDate(requireNotNull(it.item.capturedAt)) }
            .toSortedMap()
        return base(
            "${buckets.size} chronological ${if (buckets.size == 1) "date" else "dates"}",
            buckets.entries.take(20).joinToString(" • ") { (date, hits) -> "$date: ${hits.size}" }
                .ifBlank { "No deterministic capture dates were available." },
            context.hits.flatMap(SearchHit::evidence).map(EvidenceRecord::id).distinct().take(24),
        )
    }

    private fun compare(
        context: CapabilityAnswerContext,
        base: (String, String, List<String>) -> SearchAnswer,
    ): SearchAnswer {
        val grouped = context.hits.groupBy { hit ->
            when (context.plan.grouping) {
                Grouping.EVENT -> context.eventsByMedia[hit.item.id]?.title
                Grouping.PLACE -> hit.item.location
                else -> hit.item.album.ifBlank { hit.item.location }
            }.orEmpty().ifBlank { hit.item.title }
        }.entries.sortedByDescending { it.value.size }.take(2)
        if (grouped.size < 2) return base("Two comparison scopes were not resolved", "Refine the query with two places, events, albums, or result sets.", emptyList())
        val detail = grouped.joinToString(" • ") { (name, hits) ->
            val captures = hits.mapNotNull { it.item.capturedAt }
            "$name: ${hits.size} item(s), ${captures.minOrNull()?.let(::formatDate) ?: "date unavailable"} to " +
                (captures.maxOrNull()?.let(::formatDate) ?: "date unavailable")
        }
        return base(
            "${grouped[0].key} compared with ${grouped[1].key}",
            detail,
            grouped.flatMap { it.value }.flatMap(SearchHit::evidence).map(EvidenceRecord::id).distinct().take(24),
        )
    }

    private data class ParsedFact(
        val hit: SearchHit,
        val evidence: EvidenceRecord,
        val value: BigDecimal,
        val currency: String,
    )

    private fun numericFacts(hits: List<SearchHit>, field: OcrFactField): List<ParsedFact> = hits.distinctBy { it.item.id }.mapNotNull { hit ->
        val evidence = hit.evidence.firstOrNull { it.sourceField == field.sourceField } ?: return@mapNotNull null
        val number = NUMBER.find(evidence.text)?.value?.replace(",", "")?.let { runCatching { BigDecimal(it) }.getOrNull() }
            ?: return@mapNotNull null
        ParsedFact(hit, evidence, number, currency(evidence.text))
    }

    private fun currency(text: String): String = when {
        Regex("(?i)(₹|\\binr\\b|\\brs\\.?)").containsMatchIn(text) -> "INR"
        Regex("(?i)(\\busd\\b|\\$)").containsMatchIn(text) -> "USD"
        else -> "UNSPECIFIED"
    }

    private fun formatDate(epochMs: Long): String = DATE_FORMAT.format(Instant.ofEpochMilli(epochMs))

    private val NUMBER = Regex("[-+]?\\d[\\d,]*(?:\\.\\d+)?")
    private val DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault())
}
