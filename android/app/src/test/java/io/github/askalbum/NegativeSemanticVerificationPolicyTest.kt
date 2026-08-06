package io.github.anup42.askalbum

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NegativeSemanticVerificationPolicyTest {
    @Test
    fun unsupportedNegativePredicateForcesVisualVerificationEvenWhenPlannerSaysNever() {
        val plan = QueryCompiler().compile("Show photos").copy(
            semanticClauses = listOf(
                SemanticClause("dogs", polarity = Polarity.NEGATIVE, hardness = ConstraintStrength.HARD),
            ),
            verification = VerificationPolicy.NEVER,
        )

        assertTrue(VisualVerificationPolicy.requiresVerification(plan))
        assertTrue(DeterministicNegativeClausePolicy.requiresVisualRejection(plan.semanticClauses))
    }

    @Test
    fun hardScreenshotExclusionRemainsDeterministic() {
        val plan = QueryCompiler().compile("Show photos").copy(
            semanticClauses = listOf(
                SemanticClause("without screenshots", hardness = ConstraintStrength.HARD),
            ),
            verification = VerificationPolicy.NEVER,
        )

        assertFalse(VisualVerificationPolicy.requiresVerification(plan))
        assertFalse(DeterministicNegativeClausePolicy.requiresVisualRejection(plan.semanticClauses))
    }
}
