package com.samsung.agenticgallery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MerchantIdentityMatchTest {
    @Test
    fun structuredMerchantOrDocumentIdentityMatches() {
        assertTrue(matchesMerchantIdentity("swiggy", listOf("SWIGGY TEST KITCHEN"), "receipt.png"))
        assertTrue(matchesMerchantIdentity("swiggy", emptyList(), "synthetic_swiggy_receipt.png"))
    }

    @Test
    fun arbitraryOcrBodyMentionIsNotMerchantIdentity() {
        assertFalse(matchesMerchantIdentity("swiggy", listOf("Agentic Gallery"), "ask-results-screenshot.png"))
    }
}
