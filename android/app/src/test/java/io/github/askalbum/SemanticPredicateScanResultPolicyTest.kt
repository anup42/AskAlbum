package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SemanticPredicateScanResultPolicyTest {
    @Test
    fun completeCountUsesAllPersistedHitsAndDeduplicatesMedia() {
        val record = completeRecord()
        val hits = listOf(
            VectorHit("media-a", 0.9f),
            VectorHit("media-a", 0.8f),
            VectorHit("media-b", 0.7f),
        )

        assertEquals(2, SemanticPredicateScanResultPolicy.completeMatchCount(record, hits))
    }

    @Test
    fun incompleteScanCannotProvideAnExactCount() {
        val record = completeRecord().copy(
            status = SemanticPredicateScanStatus.RUNNING,
        )

        assertNull(SemanticPredicateScanResultPolicy.completeMatchCount(record, listOf(VectorHit("media-a", 0.9f))))
    }

    private fun completeRecord() = SemanticPredicateScanRecord(
        id = "scan-1",
        queryKey = "query-key",
        queryText = "exactly dog photos",
        modelVersion = "siglip-test",
        scopeHash = "scope-hash",
        eligibleCount = 3,
        indexedCount = 3,
        indexedCoverageHash = "scope-hash",
        searchedCount = 3,
        nextOrdinal = 3,
        hitCount = 2,
        status = SemanticPredicateScanStatus.COMPLETE,
        attemptCount = 1,
        error = null,
        leaseOwner = null,
        leaseExpiresAt = null,
        nextAttemptAt = 0L,
        lastProgressAt = 1L,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
