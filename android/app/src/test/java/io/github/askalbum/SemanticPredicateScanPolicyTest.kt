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
    fun negatedAndPersonBoundCountsNeverUseThePositiveOnlyExhaustiveScan() {
        val base = GalleryQueryPlan(
            originalQuery = "How many photos exactly match this condition?",
            intent = QueryIntent.COUNT,
            semanticClauses = listOf(SemanticClause("dog")),
        )
        val negative = base.copy(
            semanticClauses = listOf(SemanticClause("dog", polarity = Polarity.NEGATIVE)),
        )
        val personBound = base.copy(
            semanticClauses = listOf(
                SemanticClause(
                    text = "wearing white",
                    subject = SemanticSubject.PERSON,
                    relationToPerson = "wife",
                ),
            ),
        )

        assertFalse(SemanticPredicateScanPolicy.requested(negative))
        assertFalse(SemanticPredicateScanPolicy.requested(personBound))
    }

    @Test
    fun multiClauseSemanticCountsRemainEstimatedUntilConjunctionExecutorExists() {
        val multiClause = GalleryQueryPlan(
            originalQuery = "How many photos exactly contain a dog and a beach?",
            intent = QueryIntent.COUNT,
            semanticClauses = listOf(
                SemanticClause("dog"),
                SemanticClause("beach"),
            ),
        )

        assertFalse(SemanticPredicateScanPolicy.requested(multiClause))
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

    @Test
    fun coverageFingerprintDistinguishesDifferentMediaWithTheSameCount() {
        val eligible = setOf("a", "b", "c")
        val first = SemanticPredicateScanPolicy.coverageHash(eligible, setOf("a", "b"))
        val second = SemanticPredicateScanPolicy.coverageHash(eligible, setOf("a", "c"))

        assertNotEquals(first, second)
    }

    @Test
    fun coverageChangeResetsDormantScansButNotAHealthyLiveLease() {
        val oldHash = "old"
        val newHash = "new"

        assertTrue(SemanticPredicateScanPolicy.requiresCoverageReset(SemanticPredicateScanStatus.COMPLETE, oldHash, newHash))
        assertTrue(SemanticPredicateScanPolicy.requiresCoverageReset(SemanticPredicateScanStatus.PENDING, null, newHash))
        assertFalse(SemanticPredicateScanPolicy.requiresCoverageReset(SemanticPredicateScanStatus.RUNNING, oldHash, newHash))
    }
}
