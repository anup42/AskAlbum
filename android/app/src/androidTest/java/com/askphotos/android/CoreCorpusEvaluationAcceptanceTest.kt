package com.askphotos.android

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreCorpusEvaluationAcceptanceTest {
    @Test
    fun q01ThroughQ13ProduceStructuredEvidenceBackedEvaluation() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val runId = InstrumentationRegistry.getArguments().getString("galleryRunId")
        assumeTrue("galleryRunId was not supplied", !runId.isNullOrBlank())
        val application = instrumentation.targetContext.applicationContext as AskPhotosApplication
        val repository = application.repository
        val pack = requireNotNull(application.services.retrievalModelPackManager.current())
        assertEquals("ba1f3b0-q8-core05", pack.manifest.packVersion)
        assertEquals(0.05f, pack.manifest.minimumSimilarity, 0f)

        waitForIndex(repository, application)
        val records = mutableListOf<CaseRecord>()
        val session = "core_eval_${requireNotNull(runId).take(40)}_${System.currentTimeMillis()}"
        var q01: SearchOutcome? = null
        var q02: SearchOutcome? = null

        records.evaluate("Q01") {
            repository.searchInSession("Show photos from my Singapore trip.", session).also { outcome ->
                q01 = outcome
                assertEquals(QueryIntent.FIND_MEDIA, outcome.plan.intent)
                assertEquals(MediaScope.IMAGES, outcome.plan.mediaScope)
                val rank = requireRank(outcome, listOf("singapore_marina_bay_01"), 10)
                assertTrue(outcome.hits[rank - 1].evidence.any { it.sourceField == "event" })
                assertEvidenceClosure(outcome)
            }.metrics(listOf("singapore_marina_bay_01"))
        }
        records.evaluate("Q02") {
            val parent = requireNotNull(q01) { "Q01 did not produce a parent result set" }
            repository.searchInSession("Only Marina Bay.", session).also { outcome ->
                q02 = outcome
                assertEquals(parent.resultSetId, outcome.baseResultSetId)
                assertTrue(outcome.hits.all { hit -> parent.hits.any { it.item.id == hit.item.id } })
                val rank = requireRank(outcome, listOf("singapore_marina_bay_01"), 10)
                assertTrue(outcome.hits[rank - 1].evidence.any { it.sourceField == "image_text_embedding" })
                assertEvidenceClosure(outcome)
            }.metrics(listOf("singapore_marina_bay_01"))
        }
        records.evaluate("Q03") {
            val parent = requireNotNull(q02) { "Q02 did not produce a parent result set" }
            repository.searchInSession("What about last year?", session).also { outcome ->
                assertEquals(parent.resultSetId, outcome.baseResultSetId)
                assertTrue(outcome.hits.isEmpty())
                assertTrue(outcome.answer.evidenceIds.isEmpty() && outcome.answer.claims.isEmpty())
                assertEquals(ResultExactness.EXACT, outcome.answer.exactness)
                assertEvidenceClosure(outcome)
            }.metrics()
        }
        records.evaluate("Q04") {
            repository.search("What was the total on my latest Swiggy receipt?").also { outcome ->
                assertEquals(QueryIntent.ANSWER_FACT, outcome.plan.intent)
                assertEquals("INR 1,248", outcome.answer.headline.uppercase())
                val receipt = requireNotNull(outcome.hits.firstOrNull { it.item.filename == "synthetic_swiggy_receipt.png" })
                assertTrue(receipt.evidence.any { it.sourceField == "document_total" })
                assertEquals(ResultExactness.EXACT, outcome.answer.exactness)
                assertEvidenceClosure(outcome)
            }.metrics(listOf("synthetic_swiggy_receipt"))
        }
        records.evaluate("Q05") {
            repository.search("How many photos did I take in 2024?").also { outcome ->
                assertEquals(QueryIntent.COUNT, outcome.plan.intent)
                assertEquals(MediaScope.IMAGES, outcome.plan.mediaScope)
                assertTrue(outcome.plan.semanticClauses.isEmpty() && outcome.plan.terms.isEmpty())
                assertEquals("67 matching items", outcome.answer.headline)
                assertEquals(ResultExactness.EXACT, outcome.answer.exactness)
                assertEvidenceClosure(outcome)
            }.metrics()
        }
        records.evaluate("Q06") {
            repository.search("Show beach sunsets.").also { outcome ->
                assertEquals(QueryIntent.FIND_MEDIA, outcome.plan.intent)
                val rank = requireRank(outcome, listOf("goa_beach_01", "legacy_demo-beach"), 10)
                assertTrue(outcome.hits[rank - 1].evidence.any { it.sourceField == "image_text_embedding" })
                assertEvidenceClosure(outcome)
            }.metrics(listOf("goa_beach_01", "legacy_demo-beach"))
        }

        val people = repository.peopleIndexStatus()
        records += CaseRecord(
            id = "Q07",
            status = "SKIP",
            latencyMs = 0,
            hitCount = 0,
            expectedRank = null,
            exactness = ResultExactness.PARTIAL_INDEX.name,
            detail = peopleSkipReason(people),
        )
        records += CaseRecord(
            id = "Q08",
            status = "SKIP",
            latencyMs = 0,
            hitCount = 0,
            expectedRank = null,
            exactness = ResultExactness.PARTIAL_INDEX.name,
            detail = peopleSkipReason(people) + "; targeted Gemma verification is covered by RealGemmaVisualVerifierAcceptanceTest",
        )

        records.evaluate("Q09") {
            repository.search("Pichle saal Goa wali photos dikhao.").also { outcome ->
                assertEquals(QueryIntent.FIND_MEDIA, outcome.plan.intent)
                assertEquals(MediaScope.IMAGES, outcome.plan.mediaScope)
                val rank = requireRank(outcome, listOf("goa_beach_01"), 10)
                assertTrue(outcome.hits[rank - 1].evidence.any { it.sourceField == "event" })
                assertEvidenceClosure(outcome)
            }.metrics(listOf("goa_beach_01"))
        }
        records.evaluate("Q10") {
            repository.search("Show a receipt from a merchant that does not exist.").also { outcome ->
                assertEquals(QueryIntent.DOCUMENT_QA, outcome.plan.intent)
                assertTrue(outcome.hits.isEmpty())
                assertTrue(outcome.answer.evidenceIds.isEmpty() && outcome.answer.claims.isEmpty())
                assertEvidenceClosure(outcome)
            }.metrics()
        }
        records.evaluate("Q11") {
            repository.search("Find the yellow bicycle in my video.").also { outcome ->
                assertEquals(MediaScope.VIDEOS, outcome.plan.mediaScope)
                val video = requireNotNull(outcome.hits.firstOrNull { it.item.filename == "synthetic_video_screen_timeline.mp4" })
                val evidence = requireNotNull(video.evidence.firstOrNull { it.sourceField == "video_keyframe" })
                assertTrue(requireNotNull(evidence.timestampMs) in 5_000L..13_000L)
                assertEvidenceClosure(outcome)
            }.metrics(listOf("synthetic_video_screen_timeline"))
        }
        records.evaluate("Q12") {
            repository.search("Show photos of my dog.").also { outcome ->
                val rank = requireRank(outcome, listOf("domesticated_dog_01"), 5)
                assertTrue(outcome.hits[rank - 1].evidence.any { it.sourceField == "image_text_embedding" })
                assertEvidenceClosure(outcome)
            }.metrics(listOf("domesticated_dog_01"))
        }
        records.evaluate("Q13") {
            repository.search("Show children playing football outdoors.").also { outcome ->
                val rank = requireRank(outcome, listOf("children_football_01"), 5)
                assertTrue(outcome.hits[rank - 1].evidence.any { it.sourceField == "image_text_embedding" })
                assertEvidenceClosure(outcome)
            }.metrics(listOf("children_football_01"))
        }

        val artifact = JSONObject().apply {
            put("schemaVersion", 1)
            put("galleryRunId", requireNotNull(runId))
            put("retrievalPack", pack.manifest.packVersion)
            put("minimumSimilarity", pack.manifest.minimumSimilarity.toDouble())
            put("indexedItems", repository.allItems().count { it.indexState == IndexState.READY })
            put("passed", records.count { it.status == "PASS" })
            put("failed", records.count { it.status == "FAIL" })
            put("skipped", records.count { it.status == "SKIP" })
            put("cases", JSONArray(records.map(CaseRecord::json)))
        }
        val outputRoot = File(application.filesDir, "evaluation").also { require(it.mkdirs() || it.isDirectory) }
        File(outputRoot, "core-q01-q13.json").writeText(artifact.toString(2), Charsets.UTF_8)
        instrumentation.sendStatus(2, Bundle().apply {
            putString(
                "core_evaluation_summary",
                "CORE_Q01_Q13 passed=${artifact.getInt("passed")} failed=${artifact.getInt("failed")} " +
                    "skipped=${artifact.getInt("skipped")} threshold=${pack.manifest.minimumSimilarity}",
            )
        })
        val failed = records.filter { it.status == "FAIL" }
        assertTrue(failed.joinToString { "${it.id}: ${it.detail}" }, failed.isEmpty())
    }

    private fun waitForIndex(repository: GalleryRepository, application: AskPhotosApplication) {
        val producer = requireNotNull(application.services.semanticVectorStore.producerVersion())
        EmbeddingIndexScheduler.schedule(application)
        val deadline = SystemClock.elapsedRealtime() + INDEX_TIMEOUT_MS
        while (
            (repository.pendingItems(1).isNotEmpty() || repository.embeddingPendingItems(producer, 1).isNotEmpty() ||
                repository.keyframeEmbeddingPendingItems(producer, 1).isNotEmpty()) &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            Thread.sleep(500)
        }
        assertTrue("Core metadata indexing did not finish", repository.pendingItems(1).isEmpty())
        assertTrue("Core image embeddings did not finish", repository.embeddingPendingItems(producer, 1).isEmpty())
        assertTrue("Core video-keyframe embeddings did not finish", repository.keyframeEmbeddingPendingItems(producer, 1).isEmpty())
    }

    private suspend fun MutableList<CaseRecord>.evaluate(id: String, block: suspend () -> CaseMetrics) {
        val started = SystemClock.elapsedRealtime()
        val result = runCatching { block() }
        val elapsed = SystemClock.elapsedRealtime() - started
        this += result.fold(
            onSuccess = { metrics ->
                CaseRecord(id, "PASS", elapsed, metrics.hitCount, metrics.expectedRank, metrics.exactness, metrics.detail)
            },
            onFailure = { error ->
                CaseRecord(id, "FAIL", elapsed, 0, null, null, error.message.orEmpty().take(500))
            },
        )
    }

    private fun SearchOutcome.metrics(prefixes: List<String> = emptyList()): CaseMetrics = CaseMetrics(
        hitCount = hits.size,
        expectedRank = prefixes.takeIf(List<String>::isNotEmpty)?.let { rankByPrefix(this, it) },
        exactness = answer.exactness.name,
        detail = "intent=${plan.intent}; evidence=${answer.evidenceIds.size}",
    )

    private fun requireRank(outcome: SearchOutcome, prefixes: List<String>, maxRank: Int): Int {
        val rank = rankByPrefix(outcome, prefixes)
        assertNotNull("Expected ${prefixes.joinToString()} in top $maxRank; got ${outcome.hits.take(maxRank).map { it.item.filename }}", rank)
        assertTrue("Expected result rank $rank exceeded top-$maxRank", requireNotNull(rank) <= maxRank)
        return rank
    }

    private fun rankByPrefix(outcome: SearchOutcome, prefixes: List<String>): Int? = outcome.hits
        .indexOfFirst { hit -> prefixes.any { prefix -> hit.item.filename.startsWith(prefix) } }
        .takeIf { it >= 0 }
        ?.plus(1)

    private fun assertEvidenceClosure(outcome: SearchOutcome) {
        val evidence = outcome.hits.flatMap(SearchHit::evidence).associateBy(EvidenceRecord::id)
        assertTrue("Answer cites unknown evidence", outcome.answer.evidenceIds.all(evidence::containsKey))
        assertTrue("Claim cites unknown evidence", outcome.answer.claims.flatMap(GroundedClaim::evidenceIds).all(evidence::containsKey))
    }

    private fun peopleSkipReason(status: PeopleIndexStatus): String =
        "identity pack unavailable or not consented: enabled=${status.enabled}, identityReadyFaces=${status.identityReadyFaceCount}"

    private data class CaseMetrics(
        val hitCount: Int,
        val expectedRank: Int?,
        val exactness: String,
        val detail: String,
    )

    private data class CaseRecord(
        val id: String,
        val status: String,
        val latencyMs: Long,
        val hitCount: Int,
        val expectedRank: Int?,
        val exactness: String?,
        val detail: String,
    ) {
        fun json(): JSONObject = JSONObject().apply {
            put("id", id)
            put("status", status)
            put("latencyMs", latencyMs)
            put("hitCount", hitCount)
            if (expectedRank == null) put("expectedRank", JSONObject.NULL) else put("expectedRank", expectedRank)
            if (exactness == null) put("exactness", JSONObject.NULL) else put("exactness", exactness)
            put("detail", detail)
        }
    }

    private companion object {
        const val INDEX_TIMEOUT_MS = 10 * 60_000L
    }
}
