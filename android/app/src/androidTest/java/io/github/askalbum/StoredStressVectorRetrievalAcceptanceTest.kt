package io.github.anup42.askalbum

import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StoredStressVectorRetrievalAcceptanceTest {
    @Test
    fun completeRunScoped5kIndexReturnsExpectedDomainsWithinLatencyGate() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.getString("galleryRunId")
        val expectedCount = arguments.getString("galleryExpectedCount")?.toIntOrNull()
        assumeTrue("galleryRunId was not supplied", !runId.isNullOrBlank())
        assumeTrue("This acceptance gate requires exactly 5,000 items", expectedCount == EXPECTED_COUNT)

        val application = instrumentation.targetContext.applicationContext as AskAlbumApplication
        val safeRunId = requireNotNull(runId)
        val seedResult = JSONObject(
            File(application.filesDir, "test-seed/$safeRunId/seed-result.json").readText(),
        )
        val seededUris = seedResult.getJSONArray("createdUris").let { array ->
            (0 until array.length()).mapTo(mutableSetOf()) { array.getString(it) }
        }
        assertEquals(EXPECTED_COUNT, seedResult.getInt("createdCount"))

        val itemsById = application.repository.allItems()
            .filter { it.contentUri in seededUris }
            .associateBy(GalleryItem::id)
        assertEquals("Every recorded 5k URI must resolve to one database row", EXPECTED_COUNT, itemsById.size)
        val allowedIds = itemsById.keys
        val indexedIds = application.services.semanticVectorStore.indexedIds()
        assertEquals(
            "The run-scoped 5k index is incomplete",
            allowedIds,
            indexedIds.intersect(allowedIds),
        )

        val pack = requireNotNull(application.services.retrievalModelPackManager.current())
        assertEquals("siglip2-base-p16-224-q8", pack.manifest.packId)
        assertEquals("ba1f3b0-q8-core05", pack.manifest.packVersion)
        assertEquals(768, pack.manifest.embeddingDimension)
        assertTrue(application.services.semanticVectorStore.producerVersion()?.contains(pack.manifest.packVersion) == true)

        val cases = listOf(
            RetrievalCase("Singapore Marina Bay skyline", 0..3),
            RetrievalCase("a beach sunset by the ocean", ((4..7) + (56..63)).toSet()),
            RetrievalCase("a photo of a dog pet", 8..11),
            RetrievalCase("children playing football outdoors", 12..15),
        )
        val records = mutableListOf<CaseRecord>()
        val pssBeforeKb = Debug.getPss()
        withTimeout(2 * 60_000L) {
            cases.forEach { case ->
                val started = SystemClock.elapsedRealtime()
                val hits = application.services.semanticVectorStore.searchText(case.query, TOP_K, allowedIds)
                val elapsedMs = SystemClock.elapsedRealtime() - started
                assertTrue("${case.query} returned no stored-vector matches", hits.isNotEmpty())
                val expectedHits = hits.count { hit ->
                    val item = requireNotNull(itemsById[hit.mediaId]) { "Vector hit escaped the run scope" }
                    case.matches(item.filename)
                }
                val topFilename = requireNotNull(itemsById[hits.first().mediaId]).filename
                assertTrue("${case.query} top result was $topFilename", case.matches(topFilename))
                val evaluated = minOf(PRECISION_K, hits.size)
                val precision = hits.take(evaluated).count { hit ->
                    case.matches(requireNotNull(itemsById[hit.mediaId]).filename)
                }.toDouble() / evaluated
                assertTrue("${case.query} precision@$evaluated was $precision", precision >= MIN_PRECISION)
                records += CaseRecord(case.query, elapsedMs, hits.size, expectedHits, precision, topFilename)
            }
        }
        val warmLatencies = mutableListOf<Long>()
        repeat(LATENCY_ITERATIONS) {
            val started = SystemClock.elapsedRealtime()
            val hits = application.services.semanticVectorStore.searchText(cases[2].query, TOP_K, allowedIds)
            warmLatencies += SystemClock.elapsedRealtime() - started
            assertTrue(hits.isNotEmpty() && cases[2].matches(requireNotNull(itemsById[hits.first().mediaId]).filename))
        }
        val p95Ms = percentile95(warmLatencies)
        assertTrue("Warm 5k text-to-results p95 was ${p95Ms}ms", p95Ms <= WARM_SEARCH_P95_GATE_MS)
        val pssAfterKb = Debug.getPss()

        instrumentation.sendStatus(2, Bundle().apply {
            putString(
                "stored_5k_retrieval_trace",
                "STORED_5K_RETRIEVAL count=${allowedIds.size} vectorCount=${indexedIds.intersect(allowedIds).size} " +
                    "producer=${application.services.semanticVectorStore.producerVersion()} " +
                    "warmMs=${warmLatencies.joinToString(",")} p95Ms=$p95Ms " +
                    "pssBeforeKb=$pssBeforeKb pssAfterKb=$pssAfterKb cases=${records.joinToString(";")}",
            )
        })
    }

    private data class RetrievalCase(val query: String, val sourcePositions: Set<Int>) {
        constructor(query: String, sourcePositions: IntRange) : this(query, sourcePositions.toSet())

        fun matches(filename: String): Boolean {
            val index = STRESS_FILENAME.matchEntire(filename)?.groupValues?.get(1)?.toIntOrNull() ?: return false
            return index % SOURCE_COUNT in sourcePositions
        }
    }

    private data class CaseRecord(
        val query: String,
        val elapsedMs: Long,
        val hitCount: Int,
        val expectedHitCount: Int,
        val precision: Double,
        val topFilename: String,
    ) {
        override fun toString(): String =
            "query=${query.replace(' ', '_')},elapsedMs=$elapsedMs,hits=$hitCount,expected=$expectedHitCount," +
                "precision=$precision,top=$topFilename"
    }

    private fun percentile95(values: List<Long>): Long {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val nearestRankIndex = ((sorted.size * 95 + 99) / 100 - 1).coerceIn(sorted.indices)
        return sorted[nearestRankIndex]
    }

    private companion object {
        val STRESS_FILENAME = Regex("stress_(\\d{5})\\.jpg")
        const val EXPECTED_COUNT = 5_000
        const val SOURCE_COUNT = 81
        const val TOP_K = 20
        const val PRECISION_K = 10
        const val MIN_PRECISION = 0.6
        const val LATENCY_ITERATIONS = 5
        const val WARM_SEARCH_P95_GATE_MS = 2_000L
    }
}
