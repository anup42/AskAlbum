package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Test

class RetrievalTruthfulnessTest {
    @Test
    fun nonSemanticRetrievalIsNotReportedAsCompleteModelScan() {
        val report = RetrievalChannelReport<Any>(
            channel = RetrievalChannel.SEMANTIC,
            status = ChannelStatus.NOT_REQUIRED,
            eligibleCount = 10,
            indexedCount = 10,
            searchedCount = 0,
            hits = emptyList(),
        )

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
}
