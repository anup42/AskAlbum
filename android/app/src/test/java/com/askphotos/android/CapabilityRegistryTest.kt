package com.askphotos.android

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
