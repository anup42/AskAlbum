package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrChannelCoveragePolicyTest {
    @Test
    fun allCompleteOrSkippedRowsAreSuccessfulEvenWithoutActiveModel() {
        val coverage = IndexStageCoverage(
            eligibleCount = 4,
            statusCounts = mapOf(StageStatus.COMPLETE to 1, StageStatus.SKIPPED to 3),
        )

        assertEquals(ChannelStatus.SUCCESS, OcrChannelCoveragePolicy.status(true, coverage, modelAvailable = false))
    }

    @Test
    fun partialStageCoverageIsNotASuccessfulEmptySearch() {
        val coverage = IndexStageCoverage(
            eligibleCount = 4,
            statusCounts = mapOf(StageStatus.COMPLETE to 2, StageStatus.PENDING to 2),
        )

        assertEquals(ChannelStatus.PARTIAL, OcrChannelCoveragePolicy.status(true, coverage, modelAvailable = true))
        assertEquals("OCR_COVERAGE_PARTIAL", OcrChannelCoveragePolicy.errorCode(ChannelStatus.PARTIAL))
    }

    @Test
    fun missingCoverageAndModelIsUnavailable() {
        val coverage = IndexStageCoverage(eligibleCount = 3)

        assertEquals(ChannelStatus.UNAVAILABLE, OcrChannelCoveragePolicy.status(true, coverage, modelAvailable = false))
        assertEquals("OCR_MODEL_UNAVAILABLE", OcrChannelCoveragePolicy.errorCode(ChannelStatus.UNAVAILABLE))
    }

    @Test
    fun unrequestedOcrDoesNotAddARequiredChannel() {
        val coverage = IndexStageCoverage(eligibleCount = 5)

        assertEquals(ChannelStatus.NOT_REQUIRED, OcrChannelCoveragePolicy.status(false, coverage, modelAvailable = false))
    }

    @Test
    fun corruptProtectedOcrFailsClosedForDeterministicFacts() {
        val coverage = IndexStageCoverage(
            eligibleCount = 4,
            statusCounts = mapOf(StageStatus.COMPLETE to 4),
        )
        val integrity = OcrStoredDataIntegrity(
            checkedMediaCount = 4,
            checkedValueCount = 8,
            corruptMediaCount = 1,
            corruptValueCount = 1,
        )

        val status = OcrChannelCoveragePolicy.status(
            required = true,
            coverage = coverage,
            modelAvailable = true,
            integrity = integrity,
            requireCompleteIntegrity = true,
        )

        assertEquals(ChannelStatus.FAILED, status)
        assertEquals(OcrChannelCoveragePolicy.PROTECTED_DATA_CORRUPT, OcrChannelCoveragePolicy.errorCode(status, integrity))
    }

    @Test
    fun corruptProtectedOcrIsPartialForNonDeterministicRetrieval() {
        val coverage = IndexStageCoverage(
            eligibleCount = 4,
            statusCounts = mapOf(StageStatus.COMPLETE to 4),
        )
        val integrity = OcrStoredDataIntegrity(
            checkedMediaCount = 4,
            checkedValueCount = 8,
            corruptMediaCount = 1,
            corruptValueCount = 1,
        )

        val status = OcrChannelCoveragePolicy.status(
            required = true,
            coverage = coverage,
            modelAvailable = true,
            integrity = integrity,
        )

        assertEquals(ChannelStatus.PARTIAL, status)
        assertEquals(OcrChannelCoveragePolicy.PROTECTED_DATA_PARTIAL, OcrChannelCoveragePolicy.errorCode(status, integrity))
    }
}
