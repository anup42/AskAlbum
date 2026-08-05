package io.github.anup42.askalbum

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionEmbeddingWorkerTest {
    @Test
    fun unavailablePackRetriesOnlyWhenCaptionWorkIsPending() {
        assertTrue(CaptionEmbeddingAvailabilityPolicy.shouldRetryForUnavailablePack(hasPendingWork = true))
        assertFalse(CaptionEmbeddingAvailabilityPolicy.shouldRetryForUnavailablePack(hasPendingWork = false))
    }
}
