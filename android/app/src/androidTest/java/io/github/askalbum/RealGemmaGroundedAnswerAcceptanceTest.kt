package io.github.anup42.askalbum

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealGemmaGroundedAnswerAcceptanceTest {
    @Test
    fun installedE2bComposesOnlyClaimsWithExistingEvidence() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as AskAlbumApplication
        val status = application.modelPackManager.status()
        assumeTrue("A verified E2B pack is required", status.installed && status.tier == GemmaModelTier.E2B)
        val item = fixtureItem()
        val evidence = listOf(
            EvidenceRecord("EV_A_HAT", item.id, "visual_verification", "Person A is wearing a yellow hat", .96f, "gemma-4-e2b-acceptance"),
            EvidenceRecord("EV_B_SUIT", item.id, "visual_verification", "Person B is wearing a blue suit", .94f, "gemma-4-e2b-acceptance"),
        )
        val hit = SearchHit(item, 1.0, evidence)
        val baseline = SearchAnswer(
            headline = "Found 1 verified match",
            detail = "The 1 top candidate satisfied the required local visual conditions.",
            evidenceIds = evidence.map { it.id },
            exactness = ResultExactness.ESTIMATED_FROM_RETRIEVAL,
            indexedEligibleCount = 1,
            totalEligibleCount = 1,
        )
        val input = GroundedAnswerInput(plan(), listOf(hit), baseline)
        val pssBeforeKb = Debug.getPss()
        val started = SystemClock.elapsedRealtime()
        val result = withTimeout(6 * 60_000L) { application.services.groundedAnswerComposer.compose(input) }
        val wallMs = SystemClock.elapsedRealtime() - started
        val pssAfterCloseKb = Debug.getPss()
        val trace = result.trace
        val known = evidence.mapTo(mutableSetOf()) { it.id }
        val report = "REAL_GEMMA_ANSWER used=${trace.usedGemma} backend=${trace.backend} calls=${trace.generationCalls} " +
            "repaired=${trace.repaired} evidence=${trace.evidenceCount} loadMs=${trace.engineLoadMs} " +
            "generationMs=${trace.generationMs} closeMs=${trace.engineCloseMs} elapsedMs=${trace.elapsedMs} wallMs=$wallMs " +
            "pssBeforeKb=$pssBeforeKb pssAfterCloseKb=$pssAfterCloseKb claims=${result.answer.claims.size} fallback=${trace.fallbackReason}"
        instrumentation.sendStatus(2, Bundle().apply { putString("real_gemma_answer_trace", report) })

        assertTrue("Gemma answer composition fell back: ${trace.fallbackReason}", trace.usedGemma)
        assertTrue(trace.backend in setOf(PlannerInferenceBackend.GPU, PlannerInferenceBackend.CPU))
        assertEquals(GemmaModelTier.E2B, trace.modelTier)
        assertTrue(trace.generationCalls in 1..2)
        assertTrue(result.answer.claims.isNotEmpty())
        assertTrue(result.answer.claims.all { claim -> claim.evidenceIds.isNotEmpty() && claim.evidenceIds.all { it in known } })
        assertTrue(result.answer.evidenceIds.isNotEmpty() && result.answer.evidenceIds.all { it in known })
        assertEquals(ResultExactness.ESTIMATED_FROM_RETRIEVAL, result.answer.exactness)
        assertEquals(1, result.answer.indexedEligibleCount)
        assertEquals(1, result.answer.totalEligibleCount)
        assertTrue(trace.engineLoadMs > 0)
        assertTrue(trace.generationMs > 0)
    }

    @Test
    fun noAnswerBypassesGemmaAndCannotFabricateEvidence() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as AskAlbumApplication
        val baseline = SearchAnswer(
            headline = "No supported matches found",
            detail = "No indexed receipt matched the requested merchant.",
            evidenceIds = emptyList(),
            exactness = ResultExactness.COMPLETE_MODEL_SCAN,
            indexedEligibleCount = 8,
            totalEligibleCount = 8,
        )
        val result = application.services.groundedAnswerComposer.compose(GroundedAnswerInput(plan(), emptyList(), baseline))

        assertFalse(result.trace.usedGemma)
        assertEquals(PlannerInferenceBackend.DETERMINISTIC, result.trace.backend)
        assertEquals("No supported matches found", result.answer.headline)
        assertTrue(result.answer.evidenceIds.isEmpty())
        assertTrue(result.answer.claims.isEmpty())
    }

    private fun plan() = GalleryQueryPlan(
        originalQuery = "Show Person A wearing a yellow hat and Person B wearing a blue suit",
        intent = QueryIntent.FIND_MEDIA,
        semanticClauses = listOf(
            SemanticClause("Person A is wearing a yellow hat", hardness = ConstraintStrength.HARD, subject = SemanticSubject.PERSON),
            SemanticClause("Person B is wearing a blue suit", hardness = ConstraintStrength.HARD, subject = SemanticSubject.PERSON),
        ),
        terms = listOf("yellow hat", "blue suit"),
        verification = VerificationPolicy.REQUIRED,
    )

    private fun fixtureItem() = GalleryItem(
        id = "grounded_answer_fixture",
        filename = "grounded_answer_fixture.jpg",
        title = "Synthetic relationship fixture",
        creator = "AskAlbum acceptance suite",
        location = "Synthetic studio",
        latitude = null,
        longitude = null,
        tags = listOf("yellow hat", "blue suit"),
        description = "CC0 synthetic fixture",
        license = "CC0-1.0",
        sourceUrl = "local-synthetic-fixture",
        assetPath = null,
    )
}
