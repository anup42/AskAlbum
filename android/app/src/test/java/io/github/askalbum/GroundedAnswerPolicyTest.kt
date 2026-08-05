package io.github.anup42.askalbum

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundedAnswerPolicyTest {
    @Test
    fun semanticMediaSearchReceivesGroundedAnswerWhenModelIsAvailable() {
        val plan = GalleryQueryPlan(
            originalQuery = "Show beach sunset photos",
            intent = QueryIntent.FIND_MEDIA,
            terms = listOf("beach", "sunset"),
        )

        assertTrue(GroundedAnswerPolicy.shouldCompose(plan, true, true, false))
    }

    @Test
    fun deterministicOrResultsOnlyPathsDoNotAddAnUnrequestedGeneration() {
        val plan = GalleryQueryPlan(
            originalQuery = "Show photos",
            intent = QueryIntent.FIND_MEDIA,
            answerMode = AnswerMode.RESULTS_ONLY,
        )
        val metadataOnly = plan.copy(originalQuery = "Show photos", terms = emptyList())

        assertFalse(GroundedAnswerPolicy.shouldCompose(plan, true, true, true))
        assertFalse(GroundedAnswerPolicy.shouldCompose(metadataOnly, true, true, false))
        assertFalse(GroundedAnswerPolicy.shouldCompose(metadataOnly, true, false, false))
        assertFalse(GroundedAnswerPolicy.shouldCompose(metadataOnly, false, true, false))
    }

    @Test
    fun positiveSemanticClauseEnablesGroundedMediaAnswerWithoutFreeFormTerms() {
        val plan = GalleryQueryPlan(
            originalQuery = "Show what happened at the birthday",
            intent = QueryIntent.FIND_MEDIA,
            semanticClauses = listOf(SemanticClause("people cutting a cake")),
        )

        assertTrue(GroundedAnswerPolicy.shouldCompose(plan, true, true, false))
    }
}
