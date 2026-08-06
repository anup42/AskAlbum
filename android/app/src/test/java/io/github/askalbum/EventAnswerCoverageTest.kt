package io.github.anup42.askalbum

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventAnswerCoverageTest {
    @Test
    fun eventSummaryDoesNotUseGenericExactnessAsEventCoverage() {
        val context = eventContext(eventCoverageComplete = false)

        val answer = CapabilityAnswerExecutor.execute(context)

        assertFalse(answer.detail.contains("evaluated completely", ignoreCase = true))
        assertTrue(answer.detail.contains("current ranked retrieval pass", ignoreCase = true))
    }

    @Test
    fun eventSummaryUsesEventChannelCoverageWhenPresent() {
        val context = eventContext(eventCoverageComplete = true)

        val answer = CapabilityAnswerExecutor.execute(context)

        assertTrue(answer.detail.contains("evaluated completely", ignoreCase = true))
    }

    private fun eventContext(eventCoverageComplete: Boolean) = CapabilityAnswerContext(
        plan = GalleryQueryPlan(
            originalQuery = "Summarize my trip",
            intent = QueryIntent.EVENT_SUMMARY,
        ),
        hits = listOf(
            SearchHit(
                item = GalleryItem(
                    id = "event-media",
                    filename = "event.jpg",
                    title = "event.jpg",
                    creator = null,
                    location = "Singapore",
                    latitude = null,
                    longitude = null,
                    tags = emptyList(),
                    description = "trip",
                    license = "CC0-1.0",
                    sourceUrl = "local-fixture",
                    assetPath = "images/event.jpg",
                ),
                score = 1.0,
                evidence = emptyList(),
            ),
        ),
        matchCount = 1,
        exactness = ResultExactness.EXACT,
        indexedEligibleCount = 1,
        totalEligibleCount = 1,
        warnings = emptyList(),
        channelReports = emptyList(),
        eventCoverageComplete = eventCoverageComplete,
    )
}
