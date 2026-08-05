package io.github.anup42.askalbum

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionEmbeddingWorkerTest {
    @Test
    fun partialVectorBatchIsNotAcceptedAsSuccessfulZip() {
        assertTrue(CaptionEmbeddingBatchPolicy.hasCompleteOutput(requested = 3, returned = 3))
        assertFalse(CaptionEmbeddingBatchPolicy.hasCompleteOutput(requested = 3, returned = 2))
        assertFalse(CaptionEmbeddingBatchPolicy.hasCompleteOutput(requested = 3, returned = 4))
    }

    @Test
    fun unavailablePackRetriesOnlyWhenCaptionWorkIsPending() {
        assertTrue(CaptionEmbeddingAvailabilityPolicy.shouldRetryForUnavailablePack(hasPendingWork = true))
        assertFalse(CaptionEmbeddingAvailabilityPolicy.shouldRetryForUnavailablePack(hasPendingWork = false))
    }
}
