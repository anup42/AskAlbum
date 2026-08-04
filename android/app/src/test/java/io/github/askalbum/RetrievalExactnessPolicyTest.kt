package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Test

class RetrievalExactnessPolicyTest {
    @Test
    fun nonDeterministicQueryWithoutSemanticChannelIsEstimated() {
        val report = report(ChannelStatus.NOT_REQUIRED)

        assertEquals(
            ResultExactness.ESTIMATED_FROM_RETRIEVAL,
            RetrievalExactnessPolicy.resolve(
                allEligibleIndexed = true,
                deterministicOperation = false,
                semanticReport = report,
                verificationApplied = false,
            ),
        )
    }

    @Test
    fun explicitCompletePredicateScanHasNamedExactness() {
        assertEquals(
            ResultExactness.COMPLETE_PREDICATE_SCAN,
            RetrievalExactnessPolicy.resolve(
                allEligibleIndexed = true,
                deterministicOperation = false,
                semanticReport = report(ChannelStatus.NOT_REQUIRED),
                verificationApplied = false,
                completePredicateScan = true,
            ),
        )
    }

    private fun report(status: ChannelStatus) = RetrievalChannelReport<VectorHit>(
        channel = RetrievalChannel.SEMANTIC,
        status = status,
        eligibleCount = 10,
        indexedCount = 10,
        searchedCount = 0,
        hits = emptyList(),
    )
}
