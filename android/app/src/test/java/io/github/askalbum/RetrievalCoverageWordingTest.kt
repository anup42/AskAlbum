package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrievalCoverageWordingTest {
    @Test
    fun boundedSemanticNoResultDoesNotClaimEveryItemWasEvaluated() {
        val report = RetrievalChannelReport<Any>(
            channel = RetrievalChannel.SEMANTIC,
            status = ChannelStatus.SUCCESS,
            eligibleCount = 100,
            indexedCount = 100,
            searchedCount = 100,
            hits = emptyList(),
        )

        val detail = RetrievalCoverageWording.boundedSemanticNoResult(report)

        assertTrue(detail.contains("indexed coverage 100 of 100"))
        assertTrue(detail.contains("bounded top-K"))
        assertFalse(detail.contains("searched 100 of 100"))
        assertTrue(detail.contains("not a complete gallery predicate scan"))
    }

    @Test
    fun coverageUiLabelsBoundedVectorChannelsAsIndexed() {
        val report = RetrievalChannelReport<Any>(
            channel = RetrievalChannel.CAPTION_EMBEDDING,
            status = ChannelStatus.PARTIAL,
            eligibleCount = 100,
            indexedCount = 48,
            searchedCount = 48,
            hits = emptyList(),
        )

        assertEquals("indexed 48/100; bounded top-K", RetrievalCoverageWording.uiText(report))
    }
}
