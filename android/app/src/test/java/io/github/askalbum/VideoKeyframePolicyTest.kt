package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoKeyframePolicyTest {
    @Test
    fun samplingIsBoundedOrderedAndCoversTheTimeline() {
        val short = VideoKeyframePolicy.candidateTimestamps(18_000)
        assertEquals(listOf(2_250L, 6_750L, 11_250L, 15_750L), short)

        val long = VideoKeyframePolicy.candidateTimestamps(60 * 60 * 1_000L)
        assertEquals(VideoKeyframePolicy.MAX_KEYFRAMES, long.size)
        assertEquals(long.sorted(), long)
        assertTrue(long.first() > 0)
        assertTrue(long.last() < 60 * 60 * 1_000L)
    }

    @Test
    fun adjacentNearDuplicatesCollapseAndIdsAreStable() {
        assertTrue(VideoKeyframePolicy.shouldKeep(null, 0L))
        assertFalse(VideoKeyframePolicy.shouldKeep(0L, 0b111L))
        assertTrue(VideoKeyframePolicy.shouldKeep(0L, 0xffffL))
        assertEquals(
            VideoKeyframePolicy.stableId("media-1", 5_000),
            VideoKeyframePolicy.stableId("media-1", 5_000),
        )
        assertTrue(VideoKeyframePolicy.stableId("media-1", 5_000).matches(Regex("vf-[0-9a-f]{30}")))
    }
}
