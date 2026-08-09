package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonVerificationPromptBindingTest {
    @Test
    fun reviewedNamesAndRelationshipsBecomeStablePersonLabels() {
        val bindings = listOf(
            binding("me-cluster", "P1", setOf("Me", "Anup")),
            binding("brother-cluster", "P2", setOf("Brother", "भैया", "bhaiya")),
        )
        val conditions = listOf(
            VerificationConditionSpec("c1", "I wear a yellow hat", Polarity.POSITIVE, ConstraintStrength.HARD, SemanticSubject.PERSON, "Me"),
            VerificationConditionSpec("c2", "भैया wears a blue suit", Polarity.POSITIVE, ConstraintStrength.HARD, SemanticSubject.PERSON, "भैया"),
        )

        val result = PersonVerificationPromptBinding.bind(conditions, bindings)

        assertTrue(result[0].text.contains("P1"))
        assertTrue(result[1].text.contains("P2"))
        assertFalse(result.joinToString { it.text }.contains("भैया"))
        assertEquals("me-cluster", result[0].relationToPerson)
        assertEquals("brother-cluster", result[1].relationToPerson)
    }

    @Test
    fun negativeOtherPersonPredicateIsExpandedToRemainingLabels() {
        val bindings = listOf(
            binding("me-cluster", "P1", setOf("Me")),
            binding("wife-cluster", "P2", setOf("Wife")),
        )
        val normalized = SemanticPolarityNormalizer.normalize(
            SemanticClause(
                text = "No visible person other than Me is wearing a yellow hat",
                polarity = Polarity.POSITIVE,
                subject = SemanticSubject.PERSON,
            ),
        )
        val condition = VerificationConditionSpec(
            id = "c1",
            text = normalized.text,
            polarity = normalized.polarity,
            hardness = ConstraintStrength.HARD,
            subject = normalized.subject,
            relationToPerson = normalized.relationToPerson,
        )

        val result = PersonVerificationPromptBinding.bind(listOf(condition), bindings).single()

        assertEquals("P2 is wearing a yellow hat", result.text)
        assertEquals(Polarity.NEGATIVE, result.polarity)
    }

    @Test
    fun negativeConditionUsesPredicateVisibilityNotClauseSatisfaction() {
        val spec = VerificationConditionSpec(
            id = "c1",
            text = "P2 is wearing a green hat",
            polarity = Polarity.NEGATIVE,
            hardness = ConstraintStrength.HARD,
            subject = SemanticSubject.PERSON,
            relationToPerson = "wife-cluster",
        )

        assertTrue(
            SemanticPolarityNormalizer.conditionMatched(
                spec,
                VerificationConditionEvaluation("c1", satisfied = false, confidence = .9f, verdict = PersonVisualVerdict.VERIFIED_FALSE),
            ),
        )
        assertFalse(
            SemanticPolarityNormalizer.conditionMatched(
                spec,
                VerificationConditionEvaluation("c1", satisfied = true, confidence = .9f, verdict = PersonVisualVerdict.VERIFIED_TRUE),
            ),
        )
    }

    @Test
    fun terseGerundPredicateBecomesGrammaticalLabelledCondition() {
        val result = PersonVerificationPromptBinding.bind(
            conditions = listOf(
                VerificationConditionSpec(
                    id = "c1",
                    text = "wearing white",
                    polarity = Polarity.POSITIVE,
                    hardness = ConstraintStrength.HARD,
                    subject = SemanticSubject.PERSON,
                    relationToPerson = "me-cluster",
                ),
            ),
            bindings = listOf(binding("me-cluster", "P1", setOf("Me"))),
        ).single()

        assertEquals("P1 is wearing white", result.text)
        assertEquals("me-cluster", result.relationToPerson)
    }

    private fun binding(clusterId: String, label: String, terms: Set<String>) = PersonVerificationBinding(
        faceId = "$clusterId:face",
        clusterId = clusterId,
        stableLabel = label,
        identityTerms = terms,
        left = .1f,
        top = .1f,
        right = .3f,
        bottom = .4f,
    )
}
