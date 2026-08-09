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

    @Test
    fun eventSummaryListsOnlyReviewedVisiblePeopleAttachedToSourceMedia() {
        val context = eventContext(eventCoverageComplete = true).copy(
            peopleByMedia = mapOf(
                "event-media" to listOf(
                    IndexedPersonMetadata("person_dad", "Dad", "father", emptyList(), true, false, 8),
                    IndexedPersonMetadata("person_mother", null, "mother", emptyList(), true, false, 6),
                    IndexedPersonMetadata("person_unreviewed", "Visitor", null, emptyList(), false, false, 4),
                    IndexedPersonMetadata("person_hidden", "Hidden Person", null, emptyList(), true, true, 5),
                    IndexedPersonMetadata("person_dad_duplicate", "dad", null, emptyList(), true, false, 2),
                ),
                "unrelated-media" to listOf(
                    IndexedPersonMetadata("person_absent", "Absent Person", null, emptyList(), true, false, 7),
                ),
            ),
        )

        val answer = CapabilityAnswerExecutor.execute(context)

        assertTrue(answer.detail.contains("Reviewed people: Dad, mother."))
        assertFalse(answer.detail.contains("Visitor"))
        assertFalse(answer.detail.contains("Hidden Person"))
        assertFalse(answer.detail.contains("Absent Person"))
        assertFalse(answer.detail.contains("none requested", ignoreCase = true))
    }

    @Test
    fun eventSummaryDoesNotClaimPeopleWithoutMediaEvidence() {
        val answer = CapabilityAnswerExecutor.execute(eventContext(eventCoverageComplete = true))

        assertTrue(answer.detail.contains("Reviewed people: none identified in this event."))
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
