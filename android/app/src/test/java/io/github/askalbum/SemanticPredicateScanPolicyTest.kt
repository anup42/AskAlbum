package io.github.anup42.askalbum

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticPredicateScanPolicyTest {
    @Test
    fun onlyExplicitSemanticCountRequestsAnExhaustiveScan() {
        val bounded = GalleryQueryPlan(
            originalQuery = "How many photos contain a dog?",
            intent = QueryIntent.COUNT,
            semanticClauses = listOf(SemanticClause("dog")),
        )
        val exact = bounded.copy(originalQuery = "How many photos exactly contain a dog?")
        val nonCount = bounded.copy(intent = QueryIntent.FIND_MEDIA, originalQuery = "Show every dog photo")

        assertFalse(SemanticPredicateScanPolicy.requested(bounded))
        assertTrue(SemanticPredicateScanPolicy.requested(exact))
        assertFalse(SemanticPredicateScanPolicy.requested(nonCount))
    }

    @Test
    fun scopeAndQueryKeysAreStableButDistinct() {
        val scopeA = SemanticPredicateScanPolicy.scopeHash(setOf("a", "b"))
        val scopeB = SemanticPredicateScanPolicy.scopeHash(setOf("b", "a"))
        val first = SemanticPredicateScanPolicy.queryKey("dog", "siglip-v1", scopeA)
        val second = SemanticPredicateScanPolicy.queryKey("cat", "siglip-v1", scopeA)

        assertTrue(scopeA == scopeB)
        assertNotEquals(first, second)
        assertNotEquals(first, SemanticPredicateScanPolicy.queryKey("dog", "siglip-v2", scopeA))
    }
}
