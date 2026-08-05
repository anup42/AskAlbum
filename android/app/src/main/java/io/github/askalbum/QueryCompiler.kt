package io.github.anup42.askalbum

import java.util.Locale
import java.time.Clock
import java.time.LocalDate

/** A bounded local planner used by the offline demo pack. */
class QueryCompiler(
    private val validator: GalleryQueryPlanValidator = GalleryQueryPlanValidator(),
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val stopWords = setOf(
        "a", "an", "and", "are", "did", "do", "find", "from", "gallery", "how", "i", "many",
        "amount", "have", "in", "is", "latest", "me", "my", "of", "on", "only", "image", "images", "photo", "photos",
        "pic", "pics", "picture", "pictures",
        "please", "show", "take", "took", "the", "this", "to", "was", "were", "what", "where", "with",
        "which", "list", "place", "places", "location", "locations", "merchant", "merchants", "people", "persons",
        "recent", "day", "days", "date", "dates", "month", "months", "year", "years", "compare", "comparison", "versus", "vs",
        "bas", "dikhao", "dikhाओ", "ke", "ki", "ka", "pichle", "saal", "sirf", "wali", "wala",
        "दिखाओ", "फोटो", "फोटोस", "के", "की", "का", "पिछले", "साल", "वाली", "वाला", "सिर्फ", "सिर्फ़", "केवल",
    )

    private val aliases = mapOf(
        "bike" to "bicycle",
        "bikes" to "bicycle",
        "bicycles" to "bicycle",
        "cycle" to "bicycle",
        "meal" to "food",
        "meals" to "food",
        "dinner" to "food",
        "lunch" to "food",
        "ocean" to "beach",
        "gardens" to "garden",
        "trip" to "travel",
        "yatra" to "travel",
        "गोवा" to "goa",
        "सिंगापुर" to "singapore",
        "समुद्र" to "beach",
        "परिवार" to "family",
    )

    fun compile(query: String, activeResultIds: Set<String>? = null): GalleryQueryPlan {
        val normalized = query.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim()
        val hasStandaloneSubject = Regex(
            "\\b(photo|photos|picture|pictures|image|images|video|videos|receipt|receipts|invoice|invoices|document|documents|trip|trips)\\b",
        ).containsMatchIn(normalized)
        val isFollowUp = FollowUpLanguage.isFollowUp(query, !activeResultIds.isNullOrEmpty()) &&
            (!hasStandaloneSubject || FollowUpLanguage.permitsMediaScopeRefinement(query))
        require(!isFollowUp || !activeResultIds.isNullOrEmpty()) { "Follow-up requires an active result set" }
        val qualityFollowUp = isFollowUp && Regex("\\b(best|best one|which is the best|which one is best|close[- ]?ups?)\\b").containsMatchIn(normalized)
        val asksReceiptTotal = Regex(
            "\\b(amount paid|grand total|receipt total|total (?:on|of|for|from) .{0,40}\\b(?:receipt|invoice)|(?:receipt|invoice).{0,40}\\btotal)\\b",
        ).containsMatchIn(normalized)
        val asksDocumentAmount = Regex(
            "\\b(?:amount|line amount|item amount|amount due|amount charged|amount payable)\\b",
        ).containsMatchIn(normalized)
        val asksAllowlistedDocumentFact = Regex(
            "\\b(flight number|flight time|departure time|boarding time|order id|booking id|email address|phone number|mobile number|date|url|website)\\b",
        ).containsMatchIn(normalized) || Regex("\\b(what|which)\\s+(?:was\\s+)?(?:the\\s+)?merchant\\b").containsMatchIn(normalized)
        val asksPassword = Regex("\\b(password|passcode)\\b").containsMatchIn(normalized)
        val intent = when {
            Regex("\\b(how many|count|number of|kitne|kitni)\\b").containsMatchIn(normalized) || "कितने" in normalized || "कितनी" in normalized -> QueryIntent.COUNT
            Regex("\\b(sum|add up|combined total)\\b").containsMatchIn(normalized) -> QueryIntent.SUM
            Regex("\\b(highest|lowest|maximum|minimum|most expensive|cheapest)\\b").containsMatchIn(normalized) -> QueryIntent.MIN_MAX
            Regex("\\b(compare|comparison|versus|vs)\\b").containsMatchIn(normalized) -> QueryIntent.COMPARE
            Regex("\\b(timeline|chronological|chronology)\\b").containsMatchIn(normalized) -> QueryIntent.TIMELINE
            Regex("\\b(list|which places|which merchants|which people)\\b").containsMatchIn(normalized) -> QueryIntent.LIST
            asksReceiptTotal || asksDocumentAmount || asksAllowlistedDocumentFact || asksPassword -> QueryIntent.ANSWER_FACT
            Regex("\\b(receipt|invoice|document)\\b").containsMatchIn(normalized) -> QueryIntent.DOCUMENT_QA
            Regex("\\b(when|where|kab|kahan)\\b").containsMatchIn(normalized) || "कब" in normalized || "कहाँ" in normalized -> QueryIntent.EVENT_SUMMARY
            else -> QueryIntent.FIND_MEDIA
        }
        val followUpNoise = setOf("make", "keep", "turn", "them", "these", "those", "same", "event", "trip", "but", "close", "ups", "closeups")
        val candidateTerms = normalized.split(' ')
            .filter { it.length > 1 && it !in stopWords }
            .filterNot { qualityFollowUp && it in setOf("best", "one", "which") }
            .filterNot { isFollowUp && it in followUpNoise }
            .distinct()
        val previousYear = Regex("\\b(last year|previous year|pichle saal)\\b").containsMatchIn(normalized) || "पिछले साल" in normalized
        val explicitYear = Regex("\\b(?:19|20)\\d{2}\\b").find(normalized)?.value?.toInt()
        val timeFilter = when {
            previousYear -> calendarYear(LocalDate.now(clock).year - 1)
            explicitYear != null -> calendarYear(explicitYear)
            else -> FilterExpression.True
        }
        val temporalOnlyFollowUp = isFollowUp && timeFilter != FilterExpression.True &&
            candidateTerms.all { it in TEMPORAL_FOLLOW_UP_WORDS || it.matches(Regex("(?:19|20)\\d{2}")) }
        val originalTerms = if (temporalOnlyFollowUp) {
            emptyList()
        } else {
            candidateTerms.filterNot { term -> explicitYear != null && term == explicitYear.toString() }
        }
        val terms = originalTerms.map { aliases[it] ?: it }.distinct()
        val knownPlaces = listOf("singapore", "goa", "amsterdam", "netherlands", "california", "francisco", "marshall", "rockaway")
        val place = knownPlaces.firstOrNull { candidate -> candidate in terms }
        val comparisonScopes = if (intent == QueryIntent.COMPARE) {
            terms.filter { it in knownPlaces }.distinct()
        } else {
            emptyList()
        }
        val listMerchant = intent == QueryIntent.LIST &&
            Regex("\\b(list|which)\\s+merchants?\\b").containsMatchIn(normalized)
        val semanticClauses = if (intent in setOf(QueryIntent.SUM, QueryIntent.MIN_MAX)) {
            emptyList()
        } else {
            originalTerms.map { SemanticClause(text = it, canonicalText = aliases[it] ?: it) }
        }
        val requestedField = when {
            asksReceiptTotal -> "total"
            asksDocumentAmount -> "amount"
            asksPassword -> "password"
            Regex("\\b(flight time|departure time|boarding time)\\b").containsMatchIn(normalized) -> "flight_time"
            Regex("\\bflight number\\b").containsMatchIn(normalized) -> "flight_number"
            Regex("\\b(order id|booking id)\\b").containsMatchIn(normalized) -> "order_id"
            Regex("\\b(email|email address)\\b").containsMatchIn(normalized) -> "email"
            Regex("\\b(phone|phone number|mobile number)\\b").containsMatchIn(normalized) -> "phone"
            Regex("\\bdate\\b").containsMatchIn(normalized) -> "date"
            Regex("\\b(url|website|link)\\b").containsMatchIn(normalized) -> "url"
            Regex("\\b(what|which)\\s+(?:was\\s+)?(?:the\\s+)?merchant\\b").containsMatchIn(normalized) || listMerchant -> "merchant"
            else -> null
        }
        val merchantAfterFrom = Regex("\\breceipt\\s+from\\s+(.+)$").find(normalized)?.groupValues?.get(1)?.trim()
        val merchant = if ("receipt" in terms) {
            merchantAfterFrom ?: terms.firstOrNull { it !in setOf("receipt", "total", "paid", "grand") }
        } else {
            null
        }

        val plan = GalleryQueryPlan(
            originalQuery = query,
            intent = intent,
            mediaScope = when {
                "video" in terms || "videos" in terms -> MediaScope.VIDEOS
                "pdf" in terms || "receipt" in terms || "document" in terms || (intent == QueryIntent.LIST && requestedField == "merchant") -> MediaScope.DOCUMENTS
                normalized.split(' ').any { it in setOf("photo", "photos", "picture", "pictures", "image", "images") } -> MediaScope.IMAGES
                else -> MediaScope.ALL
            },
            filter = timeFilter,
            semanticClauses = semanticClauses,
            ocrClause = if (
                intent in setOf(QueryIntent.ANSWER_FACT, QueryIntent.DOCUMENT_QA) ||
                intent == QueryIntent.LIST && requestedField != null
            ) OcrClause(
                query = terms.joinToString(" ").takeIf(String::isNotBlank),
                merchant = merchant,
                requestedField = requestedField,
            ) else null,
            grouping = when {
                intent == QueryIntent.LIST && Regex("\\b(place|places|location|locations)\\b").containsMatchIn(normalized) -> Grouping.PLACE
                intent == QueryIntent.LIST && Regex("\\b(people|persons)\\b").containsMatchIn(normalized) -> Grouping.PERSON
                intent == QueryIntent.LIST && Regex("\\b(day|days|date|dates)\\b").containsMatchIn(normalized) -> Grouping.DAY
                "travel" in terms || place in setOf("goa", "singapore") -> Grouping.EVENT
                else -> Grouping.NONE
            },
            aggregation = when (intent) {
                QueryIntent.COUNT -> AggregationSpec(AggregationOperation.COUNT)
                QueryIntent.SUM -> AggregationSpec(AggregationOperation.SUM, requestedField ?: "total")
                QueryIntent.MIN_MAX -> AggregationSpec(AggregationOperation.MIN_MAX, requestedField ?: "total")
                else -> null
            },
            sort = when {
                qualityFollowUp -> SortSpec.QUALITY
                "latest" in normalized.split(' ') -> SortSpec.CAPTURE_TIME_DESC
                else -> SortSpec.RELEVANCE
            },
            terms = terms,
            place = if (comparisonScopes.size >= 2) null else place,
            comparisonScopes = comparisonScopes,
            baseResultIds = if (isFollowUp) activeResultIds else null,
        )
        return validator.requireValid(plan, if (isFollowUp) activeResultIds else null)
    }

    private fun calendarYear(year: Int): FilterExpression.TimeRange {
        val zone = clock.zone
        val start = LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return FilterExpression.TimeRange(start, end)
    }

    private companion object {
        val TEMPORAL_FOLLOW_UP_WORDS = setOf("about", "last", "previous", "year", "what", "now", "pichle", "saal")
    }
}
