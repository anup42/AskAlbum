package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonVerificationResultPolicyTest {
    @Test
    fun everyIdentityBoundVerdictIsRetainedButOnlyPositiveTrueMatches() {
        val binding = binding("cluster-me", "P1", "Me")
        val verdicts = listOf(
            PersonVisualVerdict.VERIFIED_TRUE,
            PersonVisualVerdict.VERIFIED_FALSE,
            PersonVisualVerdict.AMBIGUOUS,
            PersonVisualVerdict.NOT_VISIBLE,
        )
        val conditions = verdicts.indices.map { index -> condition("c$index", Polarity.POSITIVE, binding.clusterId) }
        val evaluations = verdicts.mapIndexed { index, verdict -> evaluation("c$index", verdict) }

        val resolved = PersonVerificationResultPolicy.resolve(conditions, evaluations, listOf(binding))

        assertEquals(verdicts, resolved.map { it.evaluation.verdict })
        assertTrue(resolved.all { it.binding === binding })
        assertEquals(listOf("c0"), resolved.filter(ResolvedVerificationCondition::matched).map { it.spec.id })
    }

    @Test
    fun negativeFalseMatchesWithoutChangingItsIdentityOrVerdict() {
        val me = binding("cluster-me", "P1", "Me")
        val wife = binding("cluster-wife", "P2", "Wife")
        val condition = condition("not-white", Polarity.NEGATIVE, me.clusterId)
        val evaluation = evaluation("not-white", PersonVisualVerdict.VERIFIED_FALSE)

        val resolved = PersonVerificationResultPolicy.resolve(
            conditions = listOf(condition),
            evaluations = listOf(evaluation),
            bindings = listOf(me, wife),
        ).single()

        assertTrue(resolved.matched)
        assertSame(me, resolved.binding)
        assertEquals(PersonVisualVerdict.VERIFIED_FALSE, resolved.evaluation.verdict)
    }

    private fun condition(id: String, polarity: Polarity, clusterId: String) = VerificationConditionSpec(
        id = id,
        text = "P1 is wearing white",
        polarity = polarity,
        hardness = ConstraintStrength.HARD,
        subject = SemanticSubject.PERSON,
        relationToPerson = clusterId,
    )

    private fun evaluation(id: String, verdict: PersonVisualVerdict) = VerificationConditionEvaluation(
        id = id,
        satisfied = verdict == PersonVisualVerdict.VERIFIED_TRUE,
        confidence = .93f,
        verdict = verdict,
    )

    private fun binding(clusterId: String, label: String, identity: String) = PersonVerificationBinding(
        faceId = "face-$clusterId",
        clusterId = clusterId,
        stableLabel = label,
        identityTerms = setOf(identity),
        left = .1f,
        top = .1f,
        right = .3f,
        bottom = .4f,
    )
}
