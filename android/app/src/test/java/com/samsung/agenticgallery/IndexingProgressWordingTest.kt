package com.samsung.agenticgallery

import org.junit.Assert.assertEquals
import org.junit.Test

class IndexingProgressWordingTest {
    @Test
    fun separatesPipelineBacklogsInsteadOfAddingThemAsMedia() {
        assertEquals(
            "14 media analysis | 1048 face indexing",
            IndexingProgressWording.remainingBreakdown(14, 1048),
        )
        assertEquals("1048 face indexing", IndexingProgressWording.remainingBreakdown(0, 1048))
        assertEquals("No pending items", IndexingProgressWording.remainingBreakdown(0, 0))
    }
}
