package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticEnrichmentPriorityTest {
    @Test
    fun personalWorkOutranksOldRequestedRepresentativeWork() {
        val personal = PersonalSemanticMemoryPolicy.jobReason("fixture")

        assertEquals(
            SemanticEnrichmentPriority.PERSONAL_REQUESTED,
            SemanticEnrichmentPriority.rank(personal, userRequested = true),
        )
        assertEquals(
            SemanticEnrichmentPriority.PERSONAL_BACKLOG,
            SemanticEnrichmentPriority.rank(personal, userRequested = false),
        )
        assertTrue(
            SemanticEnrichmentPriority.rank("diverse_group_representative", userRequested = true) >
                SemanticEnrichmentPriority.PERSONAL_REQUESTED,
        )
    }

    @Test
    fun orderingHasStableTieBreakersAndExcludesObsoleteErrors() {
        val order = SemanticEnrichmentPriority.sqlOrderBy()
        assertTrue(order.contains("next_attempt_at"))
        assertTrue(order.contains("updated_at"))
        assertTrue(order.endsWith("id"))
    }
}
