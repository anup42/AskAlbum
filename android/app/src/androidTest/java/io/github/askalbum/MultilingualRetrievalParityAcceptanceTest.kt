package io.github.anup42.askalbum

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MultilingualRetrievalParityAcceptanceTest {
    @Test
    fun englishHindiAndHinglishRetrieveTheSameGoaEvidenceScope() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val runId = InstrumentationRegistry.getArguments().getString("galleryRunId")
        assumeTrue("galleryRunId was not supplied", !runId.isNullOrBlank())
        val context = instrumentation.targetContext
        val repository = (context.applicationContext as AskAlbumApplication).repository
        withContext(Dispatchers.IO) { repository.initialize() }
        val seededIds = seededMediaIds(repository, context, requireNotNull(runId))
        val expectedEligibleIds = repository.allItems()
            .filter { item ->
                item.id in seededIds &&
                    item.kind == MediaKind.IMAGE &&
                    item.filename.startsWith(EXPECTED_PREFIX, ignoreCase = true)
            }
            .mapTo(mutableSetOf(), GalleryItem::id)
        assertTrue("The run-scoped corpus has no $EXPECTED_PREFIX fixture images", expectedEligibleIds.isNotEmpty())
        val deadline = SystemClock.elapsedRealtime() + INDEX_TIMEOUT_MS
        while (repository.pendingItems(1).isNotEmpty() && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(250)
        }
        assertTrue("Seeded gallery did not finish indexing within the acceptance bound", repository.pendingItems(1).isEmpty())

        val cases = listOf(
            QueryCase("english", "Show family photos from last year's Goa trip."),
            QueryCase("hindi", "पिछले साल की गोवा फैमिली फोटो दिखाओ।"),
            QueryCase("hinglish", "Pichle saal Goa wali family photos dikhao."),
        )
        val measurements = cases.map { case ->
            val started = SystemClock.elapsedRealtime()
            val outcome = repository.search(case.query, seededIds)
            val rank = outcome.hits.indexOfFirst { it.item.filename.startsWith(EXPECTED_PREFIX) } + 1
            assertEquals(case.id, case.query, outcome.plan.originalQuery)
            assertEquals("${case.id} intent", QueryIntent.FIND_MEDIA, outcome.plan.intent)
            assertEquals("${case.id} media scope", MediaScope.IMAGES, outcome.plan.mediaScope)
            assertEquals("${case.id} place", "goa", outcome.plan.place?.lowercase(Locale.ROOT))
            assertTrue("${case.id} expected $EXPECTED_PREFIX in top $TOP_K", rank in 1..TOP_K)
            assertTrue(
                "${case.id} escaped the hard-filtered Goa scope: ${outcome.hits.map { it.item.filename }}",
                outcome.hits.all { it.item.id in expectedEligibleIds },
            )
            val expectedHit = outcome.hits[rank - 1]
            assertTrue("${case.id} did not retain event evidence", expectedHit.evidence.any { it.sourceField == "event" })
            assertEquals(
                "${case.id} bounded retrieval did not report estimated exactness",
                ResultExactness.ESTIMATED_FROM_RETRIEVAL,
                outcome.answer.exactness,
            )
            assertEvidenceClosure(case.id, outcome)
            val scopedCoverage = outcome.channelReports
                .filter { it.channel in SCOPED_CHANNELS }
                .associate { it.channel to it.eligibleCount }
            assertEquals(
                "${case.id} eligible coverage did not match the run-scoped Goa set",
                expectedEligibleIds.size,
                scopedCoverage.values.maxOrNull(),
            )
            assertTrue(
                "${case.id} reported an unfiltered retrieval channel: $scopedCoverage",
                scopedCoverage.values.all { it == expectedEligibleIds.size },
            )
            Measurement(case.id, rank, SystemClock.elapsedRealtime() - started, scopedCoverage)
        }

        val expectedCoverage = measurements.first().coverage
        measurements.forEach { measurement ->
            assertEquals("${measurement.id} eligible coverage differs by language", expectedCoverage, measurement.coverage)
        }
        val reciprocalRanks = measurements.map { 1.0 / it.rank }
        val mrr = reciprocalRanks.average()
        val rankSpread = measurements.maxOf(Measurement::rank) - measurements.minOf(Measurement::rank)
        assertTrue("Multilingual MRR was $mrr", mrr >= MINIMUM_MRR)
        assertTrue("Multilingual rank spread was $rankSpread", rankSpread <= MAXIMUM_RANK_SPREAD)
        instrumentation.sendStatus(2, Bundle().apply {
            putString(
                "multilingual_retrieval_parity",
                "ranks=${measurements.associate { it.id to it.rank }}; mrr=$mrr; " +
                    "latencyMs=${measurements.associate { it.id to it.latencyMs }}; coverage=$expectedCoverage",
            )
        })
    }

    private fun assertEvidenceClosure(id: String, outcome: SearchOutcome) {
        val evidence = outcome.hits.flatMap(SearchHit::evidence).associateBy(EvidenceRecord::id)
        assertTrue("$id answer cites unknown evidence", outcome.answer.evidenceIds.all(evidence::containsKey))
        assertTrue(
            "$id claim cites unknown evidence",
            outcome.answer.claims.flatMap(GroundedClaim::evidenceIds).all(evidence::containsKey),
        )
    }

    private data class QueryCase(val id: String, val query: String)

    private data class Measurement(
        val id: String,
        val rank: Int,
        val latencyMs: Long,
        val coverage: Map<RetrievalChannel, Int>,
    )

    private companion object {
        const val EXPECTED_PREFIX = "goa_beach_01"
        const val TOP_K = 10
        const val MINIMUM_MRR = 0.5
        const val MAXIMUM_RANK_SPREAD = 2
        const val INDEX_TIMEOUT_MS = 3 * 60_000L
        val SCOPED_CHANNELS = setOf(
            RetrievalChannel.LEXICAL,
            RetrievalChannel.SEMANTIC,
            RetrievalChannel.CAPTION,
            RetrievalChannel.CAPTION_EMBEDDING,
            RetrievalChannel.EVENT,
        )
    }
}
