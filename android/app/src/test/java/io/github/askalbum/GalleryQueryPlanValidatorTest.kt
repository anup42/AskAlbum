package io.github.anup42.askalbum

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class GalleryQueryPlanValidatorTest {
    private val validator = GalleryQueryPlanValidator()

    @Test
    fun supportsEveryRequiredIntent() {
        assertEquals(
            setOf("FIND_MEDIA", "ANSWER_FACT", "LIST", "COUNT", "SUM", "MIN_MAX", "COMPARE", "TIMELINE", "EVENT_SUMMARY", "DOCUMENT_QA"),
            QueryIntent.entries.mapTo(mutableSetOf()) { it.name },
        )
    }

    @Test
    fun rejectsExcessiveLimitAndUnsafeGeneratedText() {
        val plan = GalleryQueryPlan(
            originalQuery = "photos",
            intent = QueryIntent.FIND_MEDIA,
            terms = listOf("content://invented/1", "SELECT * FROM media"),
            limit = 10_000,
        )
        val result = validator.validate(plan)
        assertFalse(result.isValid)
        assertTrue(result.errors.any { "limit" in it.lowercase() })
        assertTrue(result.errors.any { "unsafe" in it.lowercase() })
    }

    @Test
    fun allowsBenignSqlWordsInNaturalLanguage() {
        listOf(
            "Select the best photo.",
            "Show the Delete-key screenshot.",
            "Show total eclipse photos.",
        ).forEach { query ->
            val result = validator.validate(
                GalleryQueryPlan(
                    originalQuery = query,
                    intent = QueryIntent.FIND_MEDIA,
                    terms = listOf(query),
                ),
            )
            assertTrue("$query: ${result.errors}", result.isValid)
        }
    }

    @Test
    fun rejectsContradictoryHardConstraints() {
        val positive = SemanticClause("yellow hat", "yellow hat", Polarity.POSITIVE, ConstraintStrength.HARD)
        val negative = positive.copy(polarity = Polarity.NEGATIVE)
        val result = validator.validate(
            GalleryQueryPlan(
                originalQuery = "yellow hat but no yellow hat",
                intent = QueryIntent.FIND_MEDIA,
                semanticClauses = listOf(positive, negative),
            ),
        )
        assertFalse(result.isValid)
        assertTrue(result.errors.any { "contradictory" in it.lowercase() })
    }

    @Test
    fun rejectsStaleFollowUpReferences() {
        val result = validator.validate(
            GalleryQueryPlan(
                originalQuery = "Only Marina Bay",
                intent = QueryIntent.FIND_MEDIA,
                baseResultIds = setOf("old-result"),
            ),
            activeResultIds = setOf("current-result"),
        )
        assertFalse(result.isValid)
        assertTrue(result.errors.any { "follow-up" in it.lowercase() })
    }

    @Test
    fun deterministicCompilerRejectsFollowUpWithoutActiveResults() {
        assertThrows(IllegalArgumentException::class.java) {
            QueryCompiler().compile("Only Marina Bay")
        }
    }

    @Test
    fun referenceVectorIndexSupportsUpsertFilterAndDelete() = runBlocking {
        val index = ReferenceVectorIndex(2)
        index.upsert("a", floatArrayOf(1f, 0f))
        index.upsert("b", floatArrayOf(0f, 1f))
        assertEquals("a", index.search(floatArrayOf(1f, 0f), 2).first().mediaId)
        assertEquals(listOf("b"), index.search(floatArrayOf(1f, 0f), 2, setOf("b")).map { it.mediaId })
        index.delete("a")
        assertEquals(listOf("b"), index.search(floatArrayOf(1f, 0f), 2).map { it.mediaId })
    }
}
