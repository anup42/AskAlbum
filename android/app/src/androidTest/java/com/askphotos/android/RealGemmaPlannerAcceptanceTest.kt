package com.askphotos.android

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealGemmaPlannerAcceptanceTest {
    @Test
    fun installedE2bCompilesAValidatedPlanWithoutFallback() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AskPhotosApplication
        val status = context.modelPackManager.status()
        assumeTrue("A verified E2B pack is required", status.installed && status.tier == GemmaModelTier.E2B)
        val planner = LiteRtLmQueryPlanner(context.modelPackManager, context.services.inferenceResources)
        val started = SystemClock.elapsedRealtime()
        val trace = withTimeout(6 * 60_000L) {
            planner.compileWithTrace("Show beach sunset photos from 2024", activeResultIds = null)
        }
        val validation = GalleryQueryPlanValidator().validate(trace.plan)

        val report = "REAL_GEMMA_PLANNER " +
                "used=${trace.usedGemma} backend=${trace.backend} calls=${trace.generationCalls} " +
                "repaired=${trace.repaired} elapsedMs=${trace.elapsedMs} wallMs=${SystemClock.elapsedRealtime() - started} " +
                "intent=${trace.plan.intent} scope=${trace.plan.mediaScope} valid=${validation.isValid} " +
                "fallback=${trace.fallbackReason}"
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("real_gemma_trace", report) })
        assertTrue("Planner fell back: ${trace.fallbackReason}", trace.usedGemma)
        assertTrue(trace.backend in setOf(PlannerInferenceBackend.GPU, PlannerInferenceBackend.CPU))
        assertEquals(GemmaModelTier.E2B, trace.modelTier)
        assertTrue(trace.generationCalls in 1..2)
        assertTrue("Invalid real-model plan: ${validation.errors}", validation.isValid)
        assertTrue(trace.plan.intent in setOf(QueryIntent.FIND_MEDIA, QueryIntent.LIST))
        assertTrue(trace.plan.mediaScope in setOf(MediaScope.ALL, MediaScope.IMAGES))
        assertTrue(
            trace.plan.terms.any { it.contains("beach", ignoreCase = true) || it.contains("sunset", ignoreCase = true) } ||
                trace.plan.semanticClauses.any { it.text.contains("beach", ignoreCase = true) || it.text.contains("sunset", ignoreCase = true) },
        )
    }
}
