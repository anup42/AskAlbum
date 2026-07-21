package com.askphotos.android

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
        "amount", "have", "in", "is", "latest", "me", "my", "of", "on", "only", "photo", "photos", "picture", "pictures",
        "please", "show", "the", "this", "to", "was", "were", "what", "where", "with",
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
        val isFollowUp = FollowUpLanguage.isFollowUp(query)
        require(!isFollowUp || !activeResultIds.isNullOrEmpty()) { "Follow-up requires an active result set" }
        val intent = when {
            Regex("\\b(how many|count|number of|kitne|kitni)\\b").containsMatchIn(normalized) || "कितने" in normalized || "कितनी" in normalized -> QueryIntent.COUNT
            Regex("\\b(total|amount paid|wifi password|wi fi password)\\b").containsMatchIn(normalized) -> QueryIntent.ANSWER_FACT
            Regex("\\b(receipt|invoice|document)\\b").containsMatchIn(normalized) -> QueryIntent.DOCUMENT_QA
            Regex("\\b(when|where|kab|kahan)\\b").containsMatchIn(normalized) || "कब" in normalized || "कहाँ" in normalized -> QueryIntent.EVENT_SUMMARY
            else -> QueryIntent.FIND_MEDIA
        }
        val originalTerms = normalized.split(' ')
            .filter { it.length > 1 && it !in stopWords }
            .distinct()
        val terms = originalTerms.map { aliases[it] ?: it }.distinct()
        val place = listOf("singapore", "goa", "amsterdam", "netherlands", "california", "francisco", "marshall", "rockaway")
            .firstOrNull { candidate -> candidate in terms }
        val previousYear = Regex("\\b(last year|previous year|pichle saal)\\b").containsMatchIn(normalized) || "पिछले साल" in normalized
        val explicitYear = Regex("\\b(?:19|20)\\d{2}\\b").find(normalized)?.value?.toInt()
        val timeFilter = when {
            previousYear -> calendarYear(LocalDate.now(clock).year - 1)
            explicitYear != null -> calendarYear(explicitYear)
            else -> FilterExpression.True
        }
        val requestedField = when {
            Regex("\\b(total|amount paid|grand total)\\b").containsMatchIn(normalized) -> "total"
            Regex("\\b(wifi password|wi fi password)\\b").containsMatchIn(normalized) -> "password"
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
                "pdf" in terms || "receipt" in terms || "document" in terms -> MediaScope.DOCUMENTS
                else -> MediaScope.ALL
            },
            filter = timeFilter,
            semanticClauses = originalTerms.map { SemanticClause(text = it, canonicalText = aliases[it] ?: it) },
            ocrClause = if (intent in setOf(QueryIntent.ANSWER_FACT, QueryIntent.DOCUMENT_QA)) OcrClause(
                query = terms.joinToString(" "),
                merchant = merchant,
                requestedField = requestedField,
            ) else null,
            grouping = if ("travel" in terms || place in setOf("goa", "singapore")) Grouping.EVENT else Grouping.NONE,
            aggregation = if (intent == QueryIntent.COUNT) AggregationSpec(AggregationOperation.COUNT) else null,
            sort = if ("latest" in normalized.split(' ')) SortSpec.CAPTURE_TIME_DESC else SortSpec.RELEVANCE,
            terms = terms,
            place = place,
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
}
