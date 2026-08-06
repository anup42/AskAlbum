package io.github.anup42.askalbum

import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class SemanticEnrichmentSchedulingPolicyTest {
    @Test
    fun userRequestedPersonalWorkReplacesStaleConstrainedRequest() {
        assertEquals(
            ExistingWorkPolicy.REPLACE,
            SemanticEnrichmentSchedulingPolicy.workPolicy(userRequested = true),
        )
    }

    @Test
    fun backgroundEnrichmentKeepsHealthyExistingWork() {
        assertEquals(
            ExistingWorkPolicy.KEEP,
            SemanticEnrichmentSchedulingPolicy.workPolicy(userRequested = false),
        )
    }
}
