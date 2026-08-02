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
