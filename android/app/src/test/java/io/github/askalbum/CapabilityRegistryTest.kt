package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityRegistryTest {
    @Test
    fun everyPlannerVisibleIntentHasARegisteredExecutorAndAnswer() {
        assertEquals(QueryIntent.entries.toSet(), CapabilityRegistry.descriptors.map { it.intent }.toSet())
        QueryIntent.entries.forEach { intent ->
            val answer = CapabilityAnswerExecutor.execute(context(intent))
            assertTrue("$intent returned an empty headline", answer.headline.isNotBlank())
            assertTrue(CapabilityRegistry.requireExecutable(intent).executorId.isNotBlank())
        }
    }

    @Test
    fun documentAllowlistContainsEveryRequiredField() {
        assertEquals(
            setOf("total", "password", "flight_number", "flight_time", "order_id", "email", "phone", "date", "url", "merchant"),
            OcrFactAllowlist.fields.mapTo(mutableSetOf()) { it.key },
        )
    }

    @Test
    fun sumAndMinMaxUseDeterministicEvidenceAndRejectMixedCurrencies() {
        val sum = CapabilityAnswerExecutor.execute(context(QueryIntent.SUM))
        val minMax = CapabilityAnswerExecutor.execute(context(QueryIntent.MIN_MAX))
        val mixed = CapabilityAnswerExecutor.execute(
            context(QueryIntent.SUM).copy(
                hits = listOf(
                    hit("one", "Trip A", "INR 10.00", 1_700_000_000_000),
                    hit("two", "Trip B", "USD 20.00", 1_710_000_000_000),
                ),
            ),
        )

        assertEquals("INR 30", sum.headline)
        assertTrue(minMax.detail.contains("Minimum:"))
        assertEquals("Mixed currencies were not summed", mixed.headline)
    }

    @Test
    fun aggregationUsesCompleteDeterministicEvidenceInsteadOfRankedTopK() {
        val complete = hit("three", "Trip C", "INR 30.00", 1_720_000_000_000)
        val context = context(QueryIntent.SUM).copy(deterministicHits = context(QueryIntent.SUM).hits + complete)

        assertEquals("INR 60", CapabilityAnswerExecutor.execute(context).headline)
    }

    @Test
    fun listUsesCompleteDeterministicEvidenceInsteadOfRankedTopK() {
        val complete = hit("three", "Trip C", "INR 30.00", 1_720_000_000_000)
        val base = context(QueryIntent.LIST)
        val answer = CapabilityAnswerExecutor.execute(
            base.copy(deterministicHits = base.hits + complete),
        )

        assertTrue(answer.detail.contains("Trip C"))
    }

    @Test
    fun eventSummaryAndTimelineUseCompleteResolvedScope() {
        val base = context(QueryIntent.EVENT_SUMMARY)
        val complete = hit("three", "Trip C", "INR 30.00", 1_720_000_000_000)
        val completeEvent = event(3, "Trip C")
        val summary = CapabilityAnswerExecutor.execute(
            base.copy(
                hits = base.hits.take(1),
                deterministicHits = listOf(complete),
                eventsByMedia = mapOf("three" to completeEvent),
            ),
        )
        val timeline = CapabilityAnswerExecutor.execute(
            context(QueryIntent.TIMELINE).copy(
                hits = base.hits.take(1),
                deterministicHits = listOf(complete),
            ),
        )

        assertEquals("Trip C", summary.headline)
        assertTrue(summary.detail.contains("complete", ignoreCase = true))
        assertTrue(timeline.detail.contains("2024-07-03"))
        assertTrue(timeline.detail.contains("Complete dates", ignoreCase = true))
    }

    @Test
    fun minAndMaxRespectTheRequestedOperation() {
        val base = context(QueryIntent.MIN_MAX)
        val minimum = CapabilityAnswerExecutor.execute(
            base.copy(plan = base.plan.copy(aggregation = AggregationSpec(AggregationOperation.MIN, "total"))),
        )
        val maximum = CapabilityAnswerExecutor.execute(
            base.copy(plan = base.plan.copy(aggregation = AggregationSpec(AggregationOperation.MAX, "total"))),
        )

        assertEquals("INR 10", minimum.headline)
        assertEquals("INR 20", maximum.headline)
    }

    @Test
    fun passwordEvidenceAlwaysRequiresAuthentication() {
        val password = hit("secret", "Wi-Fi", "INR 10.00", 1_700_000_000_000).copy(
            evidence = listOf(EvidenceRecord("secret:password", "secret", "document_password", "mango-tree-2048", .95f)),
        )

        assertTrue(SensitiveEvidencePolicy.requiresAuthentication(password))
    }

    @Test
    fun flightTimeExtractionIsAllowlisted() {
        val entities = DocumentFactExtractor.extract(
            listOf(OcrBlockRecord("Departure time: 10:45 PM", confidence = .95f, left = 0f, top = .2f, right = 1f, bottom = .3f)),
        )

        assertTrue(entities.any { it.type == OcrEntityType.FLIGHT_TIME && it.normalizedValue == "10:45 PM" })
    }

    @Test
    fun offlineCompilerCanReachAggregationAndComparisonExecutors() {
        assertEquals(QueryIntent.SUM, QueryCompiler().compile("Sum my receipt totals").intent)
        assertEquals(QueryIntent.MIN_MAX, QueryCompiler().compile("Which receipt has the highest total?").intent)
        assertEquals(QueryIntent.COMPARE, QueryCompiler().compile("Compare Goa versus Singapore").intent)
        assertEquals(QueryIntent.TIMELINE, QueryCompiler().compile("Timeline of Singapore photos").intent)
        assertEquals(QueryIntent.LIST, QueryCompiler().compile("List places in recent photos").intent)
    }

    @Test
    fun compareExecutorUsesBothExplicitScopes() {
        val base = context(QueryIntent.COMPARE)
        val goa = hit("goa", "Goa", "INR 10.00", 1_700_000_000_000).let {
            it.copy(item = it.item.copy(location = "Goa"))
        }
        val singapore = hit("singapore", "Singapore", "INR 20.00", 1_710_000_000_000).let {
            it.copy(item = it.item.copy(location = "Singapore"))
        }

        val answer = CapabilityAnswerExecutor.execute(
            base.copy(
                hits = listOf(goa, singapore),
                deterministicHits = listOf(goa, singapore),
                comparisonScopes = listOf("goa", "singapore"),
            ),
        )

        assertTrue(answer.headline.contains("goa", ignoreCase = true))
        assertTrue(answer.headline.contains("singapore", ignoreCase = true))
        assertTrue(answer.detail.contains("Goa: 1"))
        assertTrue(answer.detail.contains("Singapore: 1"))
    }

    @Test
    fun emptyCapabilityResultsUseTypedExecutorsButVisualSearchRemainsNoResult() {
        assertTrue(shouldExecuteCapabilityWithoutMediaHits(QueryIntent.COUNT, false))
        assertTrue(shouldExecuteCapabilityWithoutMediaHits(QueryIntent.SUM, false))
        assertTrue(shouldExecuteCapabilityWithoutMediaHits(QueryIntent.DOCUMENT_QA, false))
        assertFalse(shouldExecuteCapabilityWithoutMediaHits(QueryIntent.FIND_MEDIA, false))
        assertFalse(shouldExecuteCapabilityWithoutMediaHits(QueryIntent.COUNT, true))

        val emptySum = CapabilityAnswerExecutor.execute(
            context(QueryIntent.SUM).copy(hits = emptyList(), deterministicHits = emptyList(), matchCount = 0),
        )
        assertEquals("No compatible numeric facts", emptySum.headline)
    }

    @Test
    fun listPersonUsesReviewedLabelsOnly() {
        val base = context(QueryIntent.LIST)
        val answer = CapabilityAnswerExecutor.execute(
            base.copy(
                plan = base.plan.copy(grouping = Grouping.PERSON),
                peopleByMedia = mapOf(
                    "one" to listOf(
                        IndexedPersonMetadata("person_dad", "Dad", "father", emptyList(), true, false, 2),
                    ),
                ),
            ),
        )

        assertTrue(answer.detail.contains("Dad"))
    }

    private fun context(intent: QueryIntent): CapabilityAnswerContext {
        val hits = listOf(
            hit("one", "Trip A", "INR 10.00", 1_700_000_000_000),
            hit("two", "Trip B", "INR 20.00", 1_710_000_000_000),
        )
        val aggregation = when (intent) {
            QueryIntent.COUNT -> AggregationSpec(AggregationOperation.COUNT)
            QueryIntent.SUM -> AggregationSpec(AggregationOperation.SUM, "total")
            QueryIntent.MIN_MAX -> AggregationSpec(AggregationOperation.MIN_MAX, "total")
            else -> null
        }
        val plan = GalleryQueryPlan(
            originalQuery = "fixture",
            intent = intent,
            grouping = when (intent) {
                QueryIntent.LIST -> Grouping.PLACE
                QueryIntent.COMPARE -> Grouping.EVENT
                else -> Grouping.NONE
            },
            aggregation = aggregation,
            ocrClause = if (intent in setOf(QueryIntent.ANSWER_FACT, QueryIntent.DOCUMENT_QA)) OcrClause(requestedField = "total") else null,
        )
        val events = mapOf(
            "one" to event(1, "Trip A"),
            "two" to event(2, "Trip B"),
        )
        return CapabilityAnswerContext(
            plan,
            hits,
            hits.size,
            ResultExactness.EXACT,
            hits.size,
            hits.size,
            emptyList(),
            emptyList(),
            events,
        )
    }

    private fun hit(id: String, album: String, total: String, capturedAt: Long): SearchHit {
        val item = GalleryItem(
            id = id,
            filename = "$id.jpg",
            title = album,
            creator = null,
            location = album,
            album = album,
            latitude = null,
            longitude = null,
            tags = emptyList(),
            description = album,
            license = "fixture",
            sourceUrl = "fixture",
            assetPath = null,
            capturedAt = capturedAt,
        )
        return SearchHit(
            item,
            1.0,
            listOf(EvidenceRecord("$id:total", id, "document_total", total, .95f)),
        )
    }

    private fun event(id: Long, title: String) = EventRecord(
        id,
        1_700_000_000_000,
        1_710_000_000_000,
        title,
        title,
        null,
        null,
        "TRIP",
        1,
        .9f,
        title.lowercase(),
        null,
        EventCompiler.PRODUCER_VERSION,
        false,
    )
}
