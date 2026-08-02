package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridRankFusionTest {
    @Test
    fun agreementAcrossLexicalAndVectorChannelsWins() {
        val ranked = HybridRankFusion.fuse(
            listOf(
                RankedChannel(1.0, listOf("lexical-only", "agreed", "third")),
                RankedChannel(1.0, listOf("agreed", "vector-only", "third")),
            ),
        )

        assertEquals("agreed", ranked.first().first)
        assertTrue(ranked.first().second > ranked.first { it.first == "lexical-only" }.second)
    }

    @Test
    fun duplicateIdsWithinAChannelDoNotReceiveExtraCredit() {
        val duplicate = HybridRankFusion.fuse(listOf(RankedChannel(1.0, listOf("a", "a", "b"))))
        val normal = HybridRankFusion.fuse(listOf(RankedChannel(1.0, listOf("a", "b"))))

        assertEquals(normal, duplicate)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeChannelWeightIsRejected() {
        HybridRankFusion.fuse(listOf(RankedChannel(-1.0, listOf("a"))))
    }
}
