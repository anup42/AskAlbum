package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Test

class PeopleRetrievalConstraintPolicyTest {
    private val identities = mapOf(
        "me" to setOf("me-cluster"),
        "wife" to setOf("wife-cluster"),
        "मैं" to setOf("me-cluster"),
        "पत्नी" to setOf("wife-cluster"),
    )

    @Test
    fun removesIdentityOnlyMultilingualRetrievalNoiseAfterPeopleResolution() {
        val plan = GalleryQueryPlan(
            originalQuery = "मैं और मेरी पत्नी वाली फोटो दिखाओ",
            intent = QueryIntent.FIND_MEDIA,
            peopleClauses = listOf(PersonClause("me-cluster"), PersonClause("wife-cluster")),
            terms = listOf("मैं", "पत्नी", "birthday"),
            semanticClauses = listOf(
                SemanticClause("मैं और मेरी पत्नी", "Me and my wife"),
                SemanticClause("wife cutting cake", "wife cutting cake"),
                SemanticClause(
                    text = "Me is wearing red",
                    subject = SemanticSubject.PERSON,
                    relationToPerson = "me-cluster",
                ),
            ),
        )

        val result = PeopleRetrievalConstraintPolicy.apply(plan) { identities[it].orEmpty() }

        assertEquals(listOf("birthday"), result.terms)
        assertEquals(
            listOf("wife cutting cake", "Me is wearing red"),
            result.semanticClauses.map(SemanticClause::text),
        )
    }

    @Test
    fun leavesRetrievalUntouchedWhenNoReviewedPeopleFilterExists() {
        val plan = GalleryQueryPlan(
            originalQuery = "wife photos",
            intent = QueryIntent.FIND_MEDIA,
            terms = listOf("wife"),
            semanticClauses = listOf(SemanticClause("wife", "wife")),
        )

        assertEquals(plan, PeopleRetrievalConstraintPolicy.apply(plan) { identities[it].orEmpty() })
    }
}
