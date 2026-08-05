package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Test

class EventExpansionPolicyTest {
    @Test
    fun semanticOnlySearchDoesNotTreatEveryEventMemberAsPredicateEvidence() {
        val predicateIds = EventExpansionPolicy.itemPredicateIds(
            terms = emptyList(),
            lexicalIds = setOf("event-member-without-dog"),
            semanticIds = setOf("dog-match"),
            captionIds = emptySet(),
            captionEmbeddingIds = emptySet(),
        )

        assertEquals(
            listOf("dog-match"),
            EventExpansionPolicy.mediaIdsForSearch(
                rawEventMediaIds = listOf("dog-match", "event-member-without-dog"),
                itemPredicateIds = predicateIds,
                allowContextualExpansion = false,
            ),
        )
    }

    @Test
    fun eventSummaryMayExpandToAllMembersOfTheMatchedEvent() {
        assertEquals(
            listOf("member-a", "member-b"),
            EventExpansionPolicy.mediaIdsForSearch(
                rawEventMediaIds = listOf("member-a", "member-b"),
                itemPredicateIds = setOf("member-a"),
                allowContextualExpansion = true,
            ),
        )
    }
}
