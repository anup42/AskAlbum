package io.github.anup42.askalbum

import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticEnrichmentPriorityTest {
    @Test
    fun personalBacklogOutranksUserRequestedRepresentativeWork() {
        assertTrue(
            SemanticEnrichmentPriority.forJob("personal_media:fixture", userRequested = false) >
                SemanticEnrichmentPriority.forJob("diverse_event_representative", userRequested = true),
        )
    }

    @Test
    fun interactiveVerificationHasHighestPriority() {
        assertTrue(
            SemanticEnrichmentPriority.forJob("interactive_query_verification", userRequested = false) >
                SemanticEnrichmentPriority.forJob("personal_media:fixture", userRequested = true),
        )
    }

    @Test
    fun explicitPersonalRequestPromotesAnExistingPersonalBacklogJob() {
        assertTrue(
            SemanticEnrichmentPriority.forJob("personal_media:fixture", userRequested = true) >
                SemanticEnrichmentPriority.forJob("personal_media:fixture", userRequested = false),
        )
    }
}
