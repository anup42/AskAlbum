package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventListCapabilityTest {
    @Test
    fun plannerRecognizesPlainEventListing() {
        val plan = QueryCompiler().compile("List events in my recent photos")

        assertEquals(QueryIntent.LIST, plan.intent)
        assertEquals(Grouping.EVENT, plan.grouping)
        assertTrue(plan.terms.isEmpty())
    }

    @Test
    fun listExecutorReturnsEventTitlesFromDeterministicEvidence() {
        val item = GalleryItem(
            id = "event-photo-1",
            filename = "trip.jpg",
            title = "Trip photo",
            creator = null,
            location = "Singapore",
            album = "",
            latitude = null,
            longitude = null,
            tags = emptyList(),
            description = "",
            license = "fixture",
            sourceUrl = "fixture",
            assetPath = null,
            capturedAt = 1_700_000_000_000L,
        )
        val hit = SearchHit(
            item = item,
            score = 1.0,
            evidence = listOf(
                EvidenceRecord(
                    id = "event-evidence",
                    mediaId = item.id,
                    sourceField = "event",
                    text = "Singapore trip",
                    confidence = 1f,
                    producerVersion = "fixture",
                ),
            ),
        )
        val event = EventRecord(
            id = 7L,
            startTime = 1_700_000_000_000L,
            endTime = 1_700_086_400_000L,
            title = "Singapore trip",
            locationName = "Singapore",
            latitude = null,
            longitude = null,
            eventType = "TRIP",
            memberCount = 1,
            confidence = 1f,
            searchText = "Singapore trip",
            representativeMediaId = item.id,
            producerVersion = "fixture",
            userCorrected = false,
        )
        val answer = CapabilityAnswerExecutor.execute(
            CapabilityAnswerContext(
                plan = GalleryQueryPlan(
                    originalQuery = "List events",
                    intent = QueryIntent.LIST,
                    grouping = Grouping.EVENT,
                ),
                hits = listOf(hit),
                matchCount = 1,
                exactness = ResultExactness.EXACT,
                indexedEligibleCount = 1,
                totalEligibleCount = 1,
                warnings = emptyList(),
                channelReports = listOf(
                    RetrievalChannelReport<SearchHit>(
                        channel = RetrievalChannel.EVENT,
                        status = ChannelStatus.SUCCESS,
                        eligibleCount = 1,
                        indexedCount = 1,
                        searchedCount = 1,
                        hits = listOf(hit),
                    ),
                ),
                eventsByMedia = mapOf(item.id to event),
                deterministicHits = listOf(hit),
            ),
        )

        assertTrue(answer.detail.contains("Singapore trip"))
        assertTrue(answer.evidenceIds.contains("event-evidence"))
    }
}
