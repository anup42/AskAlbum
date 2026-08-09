package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Test

class PeopleClauseMergePolicyTest {
    @Test
    fun detectedNegativeReferenceCannotBeReaddedAsPositiveDatabaseMatch() {
        val result = PeopleClauseMergePolicy.merge(
            plannerClauses = emptyList(),
            detectedClauses = listOf(PersonClause("wife", mustBePresent = false)),
            reviewedGroups = listOf(ReviewedPersonMatchGroup("wife_group", setOf("wife-cluster"))),
            resolveReviewedIds = { if (it == "wife") setOf("wife-cluster") else emptySet() },
        )

        assertEquals(listOf(PersonClause("wife", mustBePresent = false)), result)
    }

    @Test
    fun customReviewedAliasStillAddsItsAlternativeGroup() {
        val result = PeopleClauseMergePolicy.merge(
            plannerClauses = emptyList(),
            detectedClauses = emptyList(),
            reviewedGroups = listOf(ReviewedPersonMatchGroup("pooja_group", setOf("cluster-a", "cluster-b"))),
            resolveReviewedIds = { emptySet() },
        )

        assertEquals(
            setOf(
                PersonClause("cluster-a", alternativeGroup = "pooja_group"),
                PersonClause("cluster-b", alternativeGroup = "pooja_group"),
            ),
            result.toSet(),
        )
    }
}
