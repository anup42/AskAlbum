package io.github.anup42.askalbum

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionEmbeddingReconciliationPolicyTest {
    @Test
    fun reconciliationFailureRetriesEvenAfterSuccessfulEmbeddings() {
        assertTrue(CaptionEmbeddingReconciliationPolicy.shouldRetry(reconciliationFailed = true, processed = 24, failures = 0))
    }

    @Test
    fun successfulReconciliationKeepsExistingPoisonItemRule() {
        assertTrue(CaptionEmbeddingReconciliationPolicy.shouldRetry(reconciliationFailed = false, processed = 0, failures = 2))
        assertFalse(CaptionEmbeddingReconciliationPolicy.shouldRetry(reconciliationFailed = false, processed = 2, failures = 2))
        assertFalse(CaptionEmbeddingReconciliationPolicy.shouldRetry(reconciliationFailed = false, processed = 2, failures = 0))
    }
}
