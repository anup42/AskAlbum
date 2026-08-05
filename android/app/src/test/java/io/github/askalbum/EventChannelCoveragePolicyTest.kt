package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Test

class EventChannelCoveragePolicyTest {
    @Test
    fun eventExpansionIsNotCompleteWhenCoverageIsPartial() {
        val coverage = IndexStageCoverage(
            eligibleCount = 10,
            statusCounts = mapOf(StageStatus.COMPLETE to 6, StageStatus.PENDING to 4),
        )

        assertEquals(ChannelStatus.PARTIAL, EventChannelCoveragePolicy.status(true, coverage))
    }

    @Test
    fun eventExpansionIsUnavailableWhenNoMediaIsCovered() {
        val coverage = IndexStageCoverage(
            eligibleCount = 10,
            statusCounts = mapOf(StageStatus.PENDING to 10),
        )

        assertEquals(ChannelStatus.UNAVAILABLE, EventChannelCoveragePolicy.status(true, coverage))
    }

    @Test
    fun nonEventQueriesDoNotRequireEventCoverage() {
        assertEquals(
            ChannelStatus.NOT_REQUIRED,
            EventChannelCoveragePolicy.status(false, IndexStageCoverage(eligibleCount = 10)),
        )
    }
}
