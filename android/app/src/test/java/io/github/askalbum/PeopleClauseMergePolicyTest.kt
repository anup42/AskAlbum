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

        assertEquals(
            listOf(PersonClause("wife-cluster", mustBePresent = false, alternativeGroup = "wife_group")),
            result,
        )
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

    @Test
    fun unresolvedSoftPlannerIdentityCannotBlockResolvedQueryReferences() {
        val result = PeopleClauseMergePolicy.merge(
            plannerClauses = listOf(
                PersonClause("user", hardness = ConstraintStrength.SOFT),
                PersonClause("wife", hardness = ConstraintStrength.SOFT),
            ),
            detectedClauses = listOf(PersonClause("me"), PersonClause("wife")),
            reviewedGroups = listOf(
                ReviewedPersonMatchGroup("me_group", setOf("me-cluster")),
                ReviewedPersonMatchGroup("wife_group", setOf("wife-cluster")),
            ),
            resolveReviewedIds = {
                when (it) {
                    "me" -> setOf("me-cluster")
                    "wife" -> setOf("wife-cluster")
                    else -> emptySet()
                }
            },
        )

        assertEquals(
            setOf(
                PersonClause("me-cluster", alternativeGroup = "me_group"),
                PersonClause("wife-cluster", alternativeGroup = "wife_group"),
            ),
            result.toSet(),
        )
    }

    @Test
    fun unresolvedHardPlannerIdentityRemainsFailClosed() {
        val unresolved = PersonClause("unknown-person", hardness = ConstraintStrength.HARD)

        val result = PeopleClauseMergePolicy.merge(
            plannerClauses = listOf(unresolved),
            detectedClauses = emptyList(),
            reviewedGroups = emptyList(),
            resolveReviewedIds = { emptySet() },
        )

        assertEquals(listOf(unresolved), result)
    }

    @Test
    fun hardPlannerUserCanonicalizesOnlyWhenQueryExplicitlyDetectedMe() {
        val result = PeopleClauseMergePolicy.merge(
            plannerClauses = listOf(PersonClause("user", hardness = ConstraintStrength.HARD)),
            detectedClauses = listOf(PersonClause("me")),
            reviewedGroups = listOf(ReviewedPersonMatchGroup("me_group", setOf("me-cluster"))),
            resolveReviewedIds = { if (it == "me") setOf("me-cluster") else emptySet() },
        )

        assertEquals(
            listOf(PersonClause("me-cluster", alternativeGroup = "me_group")),
            result,
        )
    }
}
