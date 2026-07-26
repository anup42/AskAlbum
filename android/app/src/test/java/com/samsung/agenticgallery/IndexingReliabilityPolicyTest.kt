package com.samsung.agenticgallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexingReliabilityPolicyTest {
    @Test
    fun retryableItemIsQuarantinedAfterThreeAttempts() {
        assertEquals(StageStatus.FAILED_RETRYABLE, IndexingRetryPolicy.failedStatus(false, 1))
        assertEquals(StageStatus.FAILED_RETRYABLE, IndexingRetryPolicy.failedStatus(false, 2))
        assertEquals(StageStatus.FAILED_EXHAUSTED, IndexingRetryPolicy.failedStatus(false, 3))
        assertEquals(StageStatus.FAILED_PERMANENT, IndexingRetryPolicy.failedStatus(true, 1))
    }

    @Test
    fun mixedSuccessDoesNotBackoffWholeWorker() {
        assertFalse(IndexingWorkerResultPolicy.shouldRetryWorker(22, 2, false, true, true))
        assertTrue(IndexingWorkerResultPolicy.shouldRetryWorker(0, 2, false, true, true))
        assertTrue(IndexingWorkerResultPolicy.shouldRetryWorker(22, 0, false, false, true))
    }

    @Test
    fun retryDelayIsBoundedAndIncreasing() {
        assertEquals(30_000L, IndexingRetryPolicy.retryDelayMillis(1))
        assertEquals(60_000L, IndexingRetryPolicy.retryDelayMillis(2))
        assertEquals(120_000L, IndexingRetryPolicy.retryDelayMillis(3))
    }
}
