package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonConditionCanonicalizationPolicyTest {
    private val identities = mapOf(
        "me" to setOf("me-cluster"),
        "wife" to setOf("wife-cluster"),
    )

    @Test
    fun explicitFirstPersonAppearanceCorrectsWrongSoftPlannerBinding() {
        val plan = GalleryQueryPlan(
            originalQuery = "Show pictures with my wife where I am wearing white",
            intent = QueryIntent.FIND_MEDIA,
            semanticClauses = listOf(
                SemanticClause(
                    text = "wearing white",
                    hardness = ConstraintStrength.SOFT,
                    subject = SemanticSubject.PERSON,
                    relationToPerson = "wife",
                ),
            ),
        )

        val result = PersonConditionCanonicalizationPolicy.apply(plan.originalQuery, plan, ::resolve)
        val condition = result.semanticClauses.single()

        assertEquals("me-cluster", condition.relationToPerson)
        assertEquals(ConstraintStrength.HARD, condition.hardness)
        assertEquals(SemanticSubject.PERSON, condition.subject)
    }

    @Test
    fun explicitWifeAppearanceCanonicalizesToReviewedCluster() {
        val plan = GalleryQueryPlan(
            originalQuery = "Show pictures where my wife is wearing a white dress",
            intent = QueryIntent.FIND_MEDIA,
            semanticClauses = listOf(
                SemanticClause(
                    text = "wearing a white dress",
                    subject = SemanticSubject.PERSON,
                    relationToPerson = "wife",
                ),
            ),
        )

        val condition = PersonConditionCanonicalizationPolicy.apply(plan.originalQuery, plan, ::resolve)
            .semanticClauses.single()

        assertEquals("wife-cluster", condition.relationToPerson)
        assertEquals(ConstraintStrength.HARD, condition.hardness)
    }

    @Test
    fun clearConditionMissingFromPlannerIsAddedAsHardPersonPredicate() {
        val plan = GalleryQueryPlan(
            originalQuery = "Show me carrying a black bag",
            intent = QueryIntent.FIND_MEDIA,
            semanticClauses = emptyList(),
        )

        val condition = PersonConditionCanonicalizationPolicy.apply(plan.originalQuery, plan, ::resolve)
            .semanticClauses.single()

        assertEquals("me-cluster", condition.relationToPerson)
        assertEquals("carrying a black bag", condition.text)
        assertEquals(ConstraintStrength.HARD, condition.hardness)
    }

    @Test
    fun unknownSubjectIsNotGuessedOrRewritten() {
        val clause = SemanticClause(
            text = "wearing green",
            hardness = ConstraintStrength.HARD,
            subject = SemanticSubject.PERSON,
            relationToPerson = "unknown-person",
        )
        val plan = GalleryQueryPlan(
            originalQuery = "Show Alex wearing green",
            intent = QueryIntent.FIND_MEDIA,
            semanticClauses = listOf(clause),
        )

        val result = PersonConditionCanonicalizationPolicy.apply(plan.originalQuery, plan, ::resolve)

        assertEquals(listOf(clause), result.semanticClauses)
        assertTrue(result.semanticClauses.none { it.relationToPerson == "me-cluster" })
    }

    private fun resolve(value: String): Set<String> = identities[value].orEmpty()
}
