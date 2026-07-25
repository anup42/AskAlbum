package com.askphotos.android

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase4OrchestrationTest {
    @Test
    fun contextualFollowUpsWorkBeyondPrefixes() {
        val state = ConversationSearchState(
            sessionId = "s",
            activeResultSetId = "rs_12345678",
            activeResultIds = setOf("m1"),
        )
        listOf(
            "Only Marina Bay.",
            "Only with Dad.",
            "Show close-ups.",
            "Now 2024.",
            "Exclude screenshots.",
            "Same event but videos.",
        ).forEach { query ->
            assertTrue(query, FollowUpRefinementPolicy.isContextualFollowUp(query, state))
        }
    }

    @Test
    fun typedPatchUsesAppOwnedScopeAndNegativeExclusion() {
        val state = ConversationSearchState(
            sessionId = "s",
            activeResultSetId = "rs_12345678",
            activeResultIds = setOf("m1", "m2"),
        )
        val compiled = GalleryQueryPlan(
            originalQuery = "Exclude screenshots.",
            intent = QueryIntent.FIND_MEDIA,
            terms = listOf("exclude", "screenshots"),
            baseResultIds = state.activeResultIds,
        )
        val (patch, applied) = ResultSetPlanPatchResolver().createAndApply(compiled, state)

        assertEquals(2, patch.version)
        assertTrue(patch.operations.any { it.field == PlanPatchField.SEMANTIC_CLAUSES })
        assertTrue(applied.semanticClauses.any { it.polarity == Polarity.NEGATIVE && it.text == "screenshots" })
        assertEquals(state.activeResultIds, applied.baseResultIds)
    }

    @Test
    fun negativePredicateCanNeverBecomePositiveRequirement() {
        val normalized = SemanticPolarityNormalizer.normalize(
            SemanticClause("without screenshots", hardness = ConstraintStrength.HARD),
        )
        val spec = VerificationConditionSpec(
            "c1",
            normalized.text,
            normalized.polarity,
            normalized.hardness,
            normalized.subject,
            null,
        )

        assertEquals("screenshots", spec.text)
        assertEquals(Polarity.NEGATIVE, spec.polarity)
        assertTrue(SemanticPolarityNormalizer.conditionMatched(spec, predicateVisible = false))
        assertFalse(SemanticPolarityNormalizer.conditionMatched(spec, predicateVisible = true))
    }

    @Test
    fun matchedVideoTimestampSelectsNearestKeyframe() {
        assertEquals(
            10_000L,
            VideoKeyframeSelectionPolicy.selectTimestamp(
                available = listOf(0L, 10_000L, 20_000L),
                evidence = listOf(12_500L),
            ),
        )
    }

    @Test
    fun oneComplexQueryInitializesGemmaOnce() = runBlocking {
        val fake = FakeSharedGemmaEngine()
        val manager = GemmaSessionManager(
            resources = object : InferenceResourceManager {
                override suspend fun <T> withModel(capability: ModelCapability, block: suspend () -> T): T = block()
            },
            factory = SharedGemmaEngineFactory { _, _ -> fake },
            idleTimeoutMs = 60_000L,
        )

        manager.withEngine("fixture.task", multimodal = true) { it.engine.generateText("plan", 17) }
        manager.withEngine("fixture.task", multimodal = true) { it.engine.generateVision(byteArrayOf(1), "verify", 23) }
        manager.withEngine("fixture.task", multimodal = true) { it.engine.generateText("answer", 29) }

        assertEquals(1, manager.initializationCount)
        assertEquals(3, fake.calls)
        manager.evictNow()
        assertTrue(fake.closed)
    }

    private class FakeSharedGemmaEngine : SharedGemmaEngine {
        override val backend = PlannerInferenceBackend.CPU
        var calls = 0
        var closed = false

        override suspend fun generateText(prompt: String, seed: Int): String = "{}".also { calls++ }

        override suspend fun generateVision(imageBytes: ByteArray, prompt: String, seed: Int): String =
            "{}".also { calls++ }

        override fun close() {
            closed = true
        }
    }
}
