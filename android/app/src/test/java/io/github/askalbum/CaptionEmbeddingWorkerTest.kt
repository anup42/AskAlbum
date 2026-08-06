package io.github.anup42.askalbum

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
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
        assertEquals("UNAVAILABLE", CaptionEmbeddingAvailabilityPolicy.statusForUnavailablePack())
        assertEquals("NO_VERIFIED_RETRIEVAL_PACK", CaptionEmbeddingAvailabilityPolicy.errorCodeForUnavailablePack())
        assertTrue(CaptionEmbeddingAvailabilityPolicy.shouldRetryForUnavailablePack(hasPendingWork = true))
        assertFalse(CaptionEmbeddingAvailabilityPolicy.shouldRetryForUnavailablePack(hasPendingWork = false))
    }

    @Test
    fun missingCaptionVectorsAreRequeuedWithoutTouchingIndexedIds() {
        assertEquals(
            setOf("chunk-b"),
            CaptionVectorRepairPolicy.missingVectorIds(
                expectedCompleteIds = setOf("chunk-a", "chunk-b"),
                indexedIds = setOf("chunk-a", "stale-chunk"),
            ),
        )
    }

    @Test
    fun captionSearchCancellationIsPropagated() {
        assertTrue(CaptionVectorSearchFailurePolicy.shouldPropagate(CancellationException("cancelled")))
        assertFalse(CaptionVectorSearchFailurePolicy.shouldPropagate(IOException("embedding failed")))
    }
}
