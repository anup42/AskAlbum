package io.github.anup42.askalbum

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapabilityExecutorMatrixAcceptanceTest {
    @Test
    fun everyRegisteredCapabilityExecutesAgainstRunScopedEvidence() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val runId = InstrumentationRegistry.getArguments().getString("galleryRunId")
        assumeTrue("galleryRunId was not supplied", !runId.isNullOrBlank())
        val application = instrumentation.targetContext.applicationContext as AskAlbumApplication
        val repository = application.repository
        withContext(Dispatchers.IO) { repository.initialize() }
        val seededIds = seededMediaIds(repository, application, requireNotNull(runId))
        awaitCompleteIndex(repository, application, seededIds)

        val cases = listOf(
            Case(QueryIntent.FIND_MEDIA, "Show beach sunset photos."),
            Case(QueryIntent.LIST, "List places in my recent photos."),
            Case(QueryIntent.COUNT, "How many photos did I take in 2024?"),
            Case(QueryIntent.ANSWER_FACT, "What is the Wi-Fi password in my test card?"),
            Case(QueryIntent.DOCUMENT_QA, "What details are in my latest boarding pass document?"),
            Case(QueryIntent.SUM, "Sum my receipt totals."),
            Case(QueryIntent.MIN_MAX, "Which receipt has the highest total?"),
            Case(QueryIntent.EVENT_SUMMARY, "Summarize my Singapore trip."),
            Case(QueryIntent.TIMELINE, "Show a timeline of my Singapore trip."),
            Case(QueryIntent.COMPARE, "Compare my Goa and Singapore trips."),
        )
        assertEquals(CapabilityRegistry.descriptors.map { it.intent }, cases.map { it.intent })

        val measurements = cases.map { case ->
            val started = SystemClock.elapsedRealtime()
            val outcome = repository.search(case.query, seededIds)
            assertEquals("${case.intent} plan", case.intent, outcome.plan.intent)
            assertFalse(
                "${case.intent} reached the generic media fallback: ${outcome.answer.headline}",
                GENERIC_FOUND.matches(outcome.answer.headline.trim()),
            )
            assertEvidenceClosure(case.intent, outcome)
            assertExecutorEvidence(case.intent, outcome)
            Measurement(
                intent = case.intent,
                latencyMs = SystemClock.elapsedRealtime() - started,
                exactness = outcome.answer.exactness,
                hitCount = outcome.hits.size,
            )
        }

        instrumentation.sendStatus(2, Bundle().apply {
            putString(
                "capability_executor_matrix",
                "intents=${measurements.associate { it.intent to it.exactness }}; " +
                    "latencyMs=${measurements.associate { it.intent to it.latencyMs }}; " +
                    "hits=${measurements.associate { it.intent to it.hitCount }}",
            )
        })
    }

    private fun assertExecutorEvidence(intent: QueryIntent, outcome: SearchOutcome) {
        when (intent) {
            QueryIntent.FIND_MEDIA ->
                assertTrue("FIND_MEDIA returned no ranked evidence", outcome.hits.isNotEmpty())
            QueryIntent.LIST -> {
                assertTrue("LIST did not identify places", outcome.answer.headline.contains("place", ignoreCase = true))
                assertTrue("LIST returned no evidence", outcome.answer.evidenceIds.isNotEmpty())
            }
            QueryIntent.COUNT -> {
                assertEquals(ResultExactness.EXACT, outcome.answer.exactness)
                assertTrue(outcome.answer.headline.contains("matching items", ignoreCase = true))
            }
            QueryIntent.ANSWER_FACT -> {
                assertTrue("Sensitive fact was not authentication protected", outcome.answer.requiresAuthentication)
                assertTrue(outcome.hits.any { hit -> hit.evidence.any { it.sourceField == "document_password" } })
                assertFalse(outcome.answer.headline.contains(TEST_PASSWORD))
                assertFalse(outcome.answer.detail.contains(TEST_PASSWORD))
            }
            QueryIntent.DOCUMENT_QA -> {
                assertTrue(outcome.hits.any { it.item.filename == "synthetic_boarding_pass.png" })
                assertTrue(outcome.hits.any { hit -> hit.evidence.any { it.sourceField.startsWith("document_") } })
            }
            QueryIntent.SUM, QueryIntent.MIN_MAX -> {
                val totals = outcome.hits.flatMap(SearchHit::evidence).filter { it.sourceField == "document_total" }
                assertTrue("$intent did not receive both receipt totals", totals.map { it.mediaId }.distinct().size >= 2)
                assertTrue("$intent exposed financial facts without authentication", outcome.answer.requiresAuthentication)
            }
            QueryIntent.EVENT_SUMMARY -> {
                assertTrue(outcome.answer.detail.contains("Date range:", ignoreCase = true))
                assertTrue(outcome.answer.evidenceIds.isNotEmpty())
            }
            QueryIntent.TIMELINE -> {
                assertTrue(outcome.answer.headline.contains("chronological", ignoreCase = true))
                assertTrue(outcome.answer.detail.isNotBlank())
            }
            QueryIntent.COMPARE -> {
                assertTrue(outcome.answer.headline.contains("goa", ignoreCase = true))
                assertTrue(outcome.answer.headline.contains("singapore", ignoreCase = true))
                assertTrue(outcome.answer.evidenceIds.isNotEmpty())
            }
        }
    }

    private fun assertEvidenceClosure(intent: QueryIntent, outcome: SearchOutcome) {
        val evidence = outcome.hits.flatMap(SearchHit::evidence).associateBy(EvidenceRecord::id)
        assertTrue("$intent answer cites unknown evidence", outcome.answer.evidenceIds.all(evidence::containsKey))
        assertTrue(
            "$intent claim cites unknown evidence",
            outcome.answer.claims.flatMap(GroundedClaim::evidenceIds).all(evidence::containsKey),
        )
    }

    private fun awaitCompleteIndex(
        repository: GalleryRepository,
        application: AskAlbumApplication,
        seededIds: Set<String>,
    ) {
        val uris = repository.allItems()
            .filter { it.id in seededIds }
            .mapNotNullTo(mutableSetOf(), GalleryItem::contentUri)
        IndexScheduler.schedule(application)
        EmbeddingIndexScheduler.schedule(application)
        val requiredStages = setOf(
            IndexStage.DISCOVERY,
            IndexStage.METADATA,
            IndexStage.THUMBNAIL,
            IndexStage.VIDEO_KEYFRAMES,
            IndexStage.EMBEDDING,
            IndexStage.OCR,
            IndexStage.EVENTS,
        )
        val deadline = SystemClock.elapsedRealtime() + INDEX_TIMEOUT_MS
        var coverage = repository.indexCoverageForContentUris(uris)
        fun outstanding(): Int = coverage.stageStatuses
            .filterKeys { it in requiredStages }
            .values
            .sumOf { counts -> (counts[StageStatus.PENDING] ?: 0) + (counts[StageStatus.RUNNING] ?: 0) }
        while (outstanding() > 0 && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(500)
            coverage = repository.indexCoverageForContentUris(uris)
        }
        assertEquals("Not every seeded URI was imported", uris.size, coverage.mediaCount)
        requiredStages.forEach { stage ->
            val counts = coverage.stageStatuses.getValue(stage)
            assertEquals("$stage still pending", 0, counts[StageStatus.PENDING] ?: 0)
            assertEquals("$stage still running", 0, counts[StageStatus.RUNNING] ?: 0)
            assertEquals("$stage exhausted", 0, counts[StageStatus.FAILED_EXHAUSTED] ?: 0)
            assertEquals("$stage failed permanently", 0, counts[StageStatus.FAILED_PERMANENT] ?: 0)
        }
    }

    private data class Case(val intent: QueryIntent, val query: String)

    private data class Measurement(
        val intent: QueryIntent,
        val latencyMs: Long,
        val exactness: ResultExactness,
        val hitCount: Int,
    )

    private companion object {
        const val INDEX_TIMEOUT_MS = 10 * 60_000L
        const val TEST_PASSWORD = "mango-tree-2048"
        val GENERIC_FOUND = Regex("(?i)^found\\s+\\d+.*matches.*")
    }
}
