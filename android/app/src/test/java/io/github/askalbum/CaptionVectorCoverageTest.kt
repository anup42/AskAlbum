package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptionVectorCoverageTest {
    @Test
    fun requiredQueryWithNoCaptionChunksIsPartial() {
        assertEquals(
            ChannelStatus.PARTIAL,
            CaptionVectorCoveragePolicy.status(
                queryRequired = true,
                eligibleMediaCount = 36,
                eligibleChunkCount = 0,
                indexedChunkCount = 0,
            ),
        )
    }

    @Test
    fun emptyEligibleMediaSetIsNotRequired() {
        assertEquals(
            ChannelStatus.NOT_REQUIRED,
            CaptionVectorCoveragePolicy.status(
                queryRequired = true,
                eligibleMediaCount = 0,
                eligibleChunkCount = 0,
                indexedChunkCount = 0,
            ),
        )
    }

    @Test
    fun completeChunkCoverageIsSuccess() {
        assertEquals(
            ChannelStatus.SUCCESS,
            CaptionVectorCoveragePolicy.status(
                queryRequired = true,
                eligibleMediaCount = 12,
                eligibleChunkCount = 24,
                indexedChunkCount = 24,
            ),
        )
    }
}
