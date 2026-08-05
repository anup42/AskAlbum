package io.github.anup42.askalbum

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonVerificationBindingPolicyTest {
    @Test
    fun relationConditionUsesReviewedAliasBinding() {
        val condition = VerificationConditionSpec(
            id = "c1",
            text = "wearing white shoes",
            polarity = Polarity.POSITIVE,
            hardness = ConstraintStrength.HARD,
            subject = SemanticSubject.PERSON,
            relationToPerson = "wife",
        )
        val binding = binding("wife-cluster", setOf("Wife", "wife"))

        assertTrue(PersonVerificationBindingPolicy.conditionPersonIds(listOf(condition)).contains("wife"))
        assertTrue(PersonVerificationBindingPolicy.allConditionPeopleBound(setOf("wife"), listOf(binding)))
    }

    @Test
    fun missingOrAmbiguousBindingFailsClosed() {
        val conditionIds = setOf("wife-cluster")
        assertFalse(PersonVerificationBindingPolicy.allConditionPeopleBound(conditionIds, emptyList()))
        assertFalse(
            PersonVerificationBindingPolicy.allConditionPeopleBound(
                conditionIds,
                listOf(binding("wife-cluster", emptySet()), binding("wife-cluster", emptySet())),
            ),
        )
    }

    @Test
    fun identityBindingUsesUnicodeNormalization() {
        val binding = binding("wife-cluster", setOf("Café", "Ｗｉｆｅ"))

        assertTrue(
            PersonVerificationBindingPolicy.matchesRequestedIdentity(
                binding,
                "Cafe\u0301",
            ),
        )
        assertTrue(
            PersonVerificationBindingPolicy.matchesRequestedIdentity(
                binding,
                "wife",
            ),
        )
    }

    private fun binding(clusterId: String, terms: Set<String>) = PersonVerificationBinding(
        faceId = "$clusterId:face",
        clusterId = clusterId,
        stableLabel = "P1",
        identityTerms = terms,
        left = .1f,
        top = .1f,
        right = .4f,
        bottom = .8f,
    )
}
