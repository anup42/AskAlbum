package io.github.anup42.askalbum

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticPredicateScanBatchPolicyTest {
    @Test
    fun onlySuccessfulCompleteChannelBatchesMayAdvanceScan() {
        val success = report(ChannelStatus.SUCCESS)
        assertTrue(SemanticPredicateScanPolicy.canCommitBatch(success))

        listOf(ChannelStatus.PARTIAL, ChannelStatus.UNAVAILABLE, ChannelStatus.FAILED).forEach { status ->
            assertFalse(SemanticPredicateScanPolicy.canCommitBatch(report(status)))
        }
    }

    @Test
    fun successfulReportWithAnErrorCodeCannotAdvanceScan() {
        val report = report(ChannelStatus.SUCCESS).copy(errorCode = "VECTOR_COVERAGE_PARTIAL")
        assertFalse(SemanticPredicateScanPolicy.canCommitBatch(report))
    }

    private fun report(status: ChannelStatus): RetrievalChannelReport<VectorHit> = RetrievalChannelReport(
        channel = RetrievalChannel.SEMANTIC,
        status = status,
        eligibleCount = 2,
        indexedCount = if (status == ChannelStatus.SUCCESS) 2 else 0,
        searchedCount = if (status == ChannelStatus.SUCCESS) 2 else 0,
        hits = listOf(VectorHit("media-1", 0.8f)),
        modelVersion = "siglip@test",
    )
}
