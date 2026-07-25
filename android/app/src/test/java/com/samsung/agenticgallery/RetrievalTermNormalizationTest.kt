package com.samsung.agenticgallery

import org.junit.Assert.assertEquals
import org.junit.Test

class RetrievalTermNormalizationTest {
    @Test
    fun multiwordModelTermsAreSplitForDeterministicRetrievalChannels() {
        assertEquals(
            listOf("photos", "singapore", "trip", "marina", "bay"),
            RetrievalTerms.normalize(listOf("photos", "Singapore trip", "Marina Bay")),
        )
    }
}
