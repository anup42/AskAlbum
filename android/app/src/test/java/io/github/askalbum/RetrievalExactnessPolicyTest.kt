package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Test

class RetrievalExactnessPolicyTest {
    @Test
    fun completePredicateScanIsTheOnlySemanticPathThatCanBeComplete() {
        val success = RetrievalChannelReport<Any>(
            channel = RetrievalChannel.SEMANTIC,
            status = ChannelStatus.SUCCESS,
            eligibleCount = 10,
            indexedCount = 10,
            searchedCount = 10,
            hits = emptyList(),
        )
        val partial = success.copy(status = ChannelStatus.PARTIAL, searchedCount = 4)

        assertEquals(
            ResultExactness.COMPLETE_PREDICATE_SCAN,
            RetrievalExactnessPolicy.resolve(true, false, success, false, completePredicateScan = true),
        )
        assertEquals(
            ResultExactness.ESTIMATED_FROM_RETRIEVAL,
            RetrievalExactnessPolicy.resolve(true, false, success, false, completePredicateScan = false),
        )
        assertEquals(
            ResultExactness.PARTIAL_INDEX,
            RetrievalExactnessPolicy.resolve(true, false, partial, false, completePredicateScan = true),
        )
    }

    @Test
    fun completeSemanticScanWithBoundedVisualVerificationIsNotExact() {
        val success = RetrievalChannelReport<Any>(
            channel = RetrievalChannel.SEMANTIC,
            status = ChannelStatus.SUCCESS,
            eligibleCount = 10,
            indexedCount = 10,
            searchedCount = 10,
            hits = emptyList(),
        )

        assertEquals(
            ResultExactness.ESTIMATED_FROM_RETRIEVAL,
            RetrievalExactnessPolicy.resolve(
                allEligibleIndexed = true,
                deterministicOperation = false,
                semanticReport = success,
                verificationApplied = true,
                completePredicateScan = true,
            ),
        )
    }
}
