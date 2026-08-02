package io.github.anup42.askalbum

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
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
    fun installedE2bCompilesEnglishHindiAndHinglishPlansWithoutFallback() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext as AskAlbumApplication
        val status = context.modelPackManager.status()
        assumeTrue("A verified E2B pack is required", status.installed && status.tier == GemmaModelTier.E2B)
        val planner = LiteRtLmQueryPlanner(context.modelPackManager, context.services.inferenceResources)
        val previousYear = LocalDate.now().year - 1
        val cases = listOf(
            PlanCase("english", "Show beach sunset photos from 2024", listOf(setOf("beach"), setOf("sunset")), 2024),
            PlanCase("hindi", "पिछले साल गोवा वाली फोटो दिखाओ", listOf(setOf("goa", "गोवा")), previousYear),
            PlanCase(
                "hinglish",
                "Pichle saal Goa wali family photos dikhao, but only close-ups",
                listOf(setOf("goa"), setOf("family"), setOf("close")),
                previousYear,
            ),
        )

        cases.forEach { case ->
            val pssBeforeKb = Debug.getPss()
            val started = SystemClock.elapsedRealtime()
            val trace = withTimeout(6 * 60_000L) {
                planner.compileWithTrace(case.query, activeResultIds = null)
            }
            val wallMs = SystemClock.elapsedRealtime() - started
            val pssAfterCloseKb = Debug.getPss()
            val validation = GalleryQueryPlanValidator().validate(trace.plan)
            val searchable = searchableText(trace.plan)
            val expectedRange = calendarYear(case.expectedYear)
            val ranges = timeRanges(trace.plan.filter)

            val report = "REAL_GEMMA_PLANNER case=${case.id} used=${trace.usedGemma} backend=${trace.backend} " +
                "calls=${trace.generationCalls} repaired=${trace.repaired} loadMs=${trace.engineLoadMs} " +
                "generationMs=${trace.generationMs} closeMs=${trace.engineCloseMs} elapsedMs=${trace.elapsedMs} " +
                "wallMs=$wallMs pssBeforeKb=$pssBeforeKb pssAfterCloseKb=$pssAfterCloseKb " +
                "overlay=${trace.deterministicOverlayApplied} intent=${trace.plan.intent} " +
                "scope=${trace.plan.mediaScope} valid=${validation.isValid} fallback=${trace.fallbackReason}"
            instrumentation.sendStatus(2, Bundle().apply { putString("real_gemma_trace_${case.id}", report) })

            assertTrue("${case.id} fell back: ${trace.fallbackReason}", trace.usedGemma)
            assertTrue(trace.backend in setOf(PlannerInferenceBackend.GPU, PlannerInferenceBackend.CPU))
            assertEquals(GemmaModelTier.E2B, trace.modelTier)
            assertTrue(trace.generationCalls in 1..2)
            assertTrue("${case.id} invalid: ${validation.errors}", validation.isValid)
            assertTrue(trace.plan.intent in setOf(QueryIntent.FIND_MEDIA, QueryIntent.LIST))
            assertTrue(trace.plan.mediaScope in setOf(MediaScope.ALL, MediaScope.IMAGES))
            assertTrue("${case.id} did not record model load", trace.engineLoadMs > 0)
            assertTrue("${case.id} did not record generation", trace.generationMs > 0)
            assertTrue("${case.id} close timing is invalid", trace.engineCloseMs >= 0)
            assertTrue("${case.id} elapsed timing is incomplete", trace.elapsedMs >= trace.engineLoadMs + trace.generationMs + trace.engineCloseMs)
            assertEquals("${case.id} exact Kotlin time range", expectedRange, ranges.single())
            case.expectedTermGroups.forEach { alternatives ->
                assertTrue("${case.id} missing one of $alternatives in $searchable", alternatives.any(searchable::contains))
            }
        }
    }

    private fun searchableText(plan: GalleryQueryPlan): String = buildList {
        addAll(plan.terms)
        plan.place?.let(::add)
        plan.semanticClauses.forEach { clause ->
            add(clause.text)
            clause.canonicalText?.let(::add)
        }
        collectAlbums(plan.filter, this)
    }.joinToString(" ").lowercase(Locale.ROOT)

    private fun collectAlbums(filter: FilterExpression, output: MutableList<String>) {
        when (filter) {
            is FilterExpression.AlbumIs -> output += filter.album
            is FilterExpression.And -> filter.clauses.forEach { collectAlbums(it, output) }
            else -> Unit
        }
    }

    private fun timeRanges(filter: FilterExpression): List<FilterExpression.TimeRange> = when (filter) {
        is FilterExpression.TimeRange -> listOf(filter)
        is FilterExpression.And -> filter.clauses.flatMap(::timeRanges)
        else -> emptyList()
    }

    private fun calendarYear(year: Int): FilterExpression.TimeRange {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return FilterExpression.TimeRange(start, end)
    }

    private data class PlanCase(
        val id: String,
        val query: String,
        val expectedTermGroups: List<Set<String>>,
        val expectedYear: Int,
    )
}
