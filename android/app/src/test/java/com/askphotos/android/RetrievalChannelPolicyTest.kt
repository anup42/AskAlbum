package com.askphotos.android

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetrievalChannelPolicyTest {
    @Test
    fun missingModelIsUnavailableAndDoesNotExecuteSearch() = runBlocking {
        var searched = false
        val report = SemanticChannelReporter.execute(
            "dog", null, 2, setOf("a", "b"), 100,
            indexedIds = { error("must not load an index without a pack") },
            search = { _, _, _ -> searched = true; emptyList() },
        )

        assertEquals(ChannelStatus.UNAVAILABLE, report.status)
        assertFalse(searched)
    }

    @Test
    fun embeddingFailureIsFailedInsteadOfSuccessfulEmpty() = runBlocking {
        val report = SemanticChannelReporter.execute(
            "dog", "siglip@test", 1, setOf("a"), 100,
            indexedIds = { setOf("a") },
            search = { _, _, _ -> error("embedding failed") },
        )

        assertEquals(ChannelStatus.FAILED, report.status)
        assertEquals("TEXT_EMBEDDING_OR_VECTOR_SEARCH_FAILED", report.errorCode)
    }

    @Test
    fun partialVectorCoverageAndZeroHitsRemainTyped() = runBlocking {
        val partial = SemanticChannelReporter.execute(
            "dog", "siglip@test", 2, setOf("a", "b"), 100,
            indexedIds = { setOf("a") },
            search = { _, _, _ -> listOf(VectorHit("a", .8f)) },
        )
        val zero = SemanticChannelReporter.execute(
            "unicorn", "siglip@test", 1, setOf("a"), 100,
            indexedIds = { setOf("a") },
            search = { _, _, _ -> emptyList() },
        )

        assertEquals(ChannelStatus.PARTIAL, partial.status)
        assertEquals(1, partial.indexedCount)
        assertEquals(ChannelStatus.SUCCESS, zero.status)
        assertTrue(zero.hits.isEmpty())
    }

    @Test
    fun hardFilterIsAppliedBeforeTopK() = runBlocking {
        val index = ReferenceVectorIndex(2)
        repeat(101) { index.upsert("global-$it", floatArrayOf(1f, 0f)) }
        index.upsert("valid-2024", floatArrayOf(.8f, .6f))

        val global = index.search(floatArrayOf(1f, 0f), 100)
        val filtered = index.search(floatArrayOf(1f, 0f), 100, setOf("valid-2024"))

        assertFalse(global.any { it.mediaId == "valid-2024" })
        assertEquals(listOf("valid-2024"), filtered.map(VectorHit::mediaId))
    }

    @Test
    fun originalCanonicalHindiAndHinglishQueriesArePreserved() {
        val english = variants("Show family photos from last year's Goa trip.", "family on Goa trip")
        val hindi = variants("पिछले साल की गोवा फैमिली फोटो दिखाओ।", "family photos from last year's Goa trip")
        val hinglish = variants("Pichle saal Goa wali family photos dikhao.", "family photos from last year's Goa trip")

        assertTrue(english.first().startsWith("Show family"))
        assertTrue(hindi.any { it == "family photos from last year's Goa trip" })
        assertTrue(hinglish.any { it == "family photos from last year's Goa trip" })
    }

    @Test
    fun semanticCountsAndExactnessNeverClaimCompleteScan() {
        val report = RetrievalChannelReport<VectorHit>(
            RetrievalChannel.SEMANTIC,
            ChannelStatus.SUCCESS,
            1_000,
            1_000,
            1_000,
            emptyList(),
        )

        assertEquals(
            ResultExactness.ESTIMATED_FROM_RETRIEVAL,
            RetrievalExactnessPolicy.resolve(true, false, report, false),
        )
        assertEquals("100 matches in the current retrieval pass", RetrievalAnswerWording.countHeadline(100, true))
    }

    @Test
    fun totalEclipseDoesNotBecomeDocumentFactIntent() {
        val plan = QueryCompiler().compile("Show total eclipse photos.")

        assertEquals(QueryIntent.FIND_MEDIA, plan.intent)
    }

    private fun variants(query: String, canonical: String): List<String> = SemanticQueryVariants.from(
        GalleryQueryPlan(
            originalQuery = query,
            intent = QueryIntent.FIND_MEDIA,
            terms = listOf("family", "goa"),
            semanticClauses = listOf(SemanticClause("family Goa", canonical)),
        ),
    )
}
