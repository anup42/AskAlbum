package io.github.anup42.askalbum

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
    fun fullyIndexedVideoKeyframesDoNotMakeMediaCoverageLookPartial() = runBlocking {
        val report = SemanticChannelReporter.execute(
            "video", "siglip@test", 1, setOf("video", "video-keyframe-1"), 100,
            indexedIds = { setOf("video", "video-keyframe-1") },
            search = { _, _, _ -> emptyList() },
        )

        assertEquals(ChannelStatus.SUCCESS, report.status)
        assertEquals(1, report.eligibleCount)
        assertEquals(1, report.indexedCount)
        assertEquals(1, report.searchedCount)
    }

    @Test
    fun eligibleMediaWithoutAnyVectorIdsIsPartialAndDoesNotSearch() = runBlocking {
        var searched = false
        val report = SemanticChannelReporter.execute(
            "dog", "siglip@test", 3, emptySet(), 100,
            indexedIds = { emptySet() },
            search = { _, _, _ -> searched = true; emptyList() },
        )

        assertEquals(ChannelStatus.PARTIAL, report.status)
        assertEquals(3, report.eligibleCount)
        assertEquals(0, report.indexedCount)
        assertEquals("VECTOR_COVERAGE_PARTIAL", report.errorCode)
        assertFalse(searched)
    }

    @Test
    fun eligibleMediaWithMissingVectorIdsIsPartialEvenWhenIndexedSubsetIsComplete() = runBlocking {
        val report = SemanticChannelReporter.execute(
            "dog", "siglip@test", 3, setOf("a", "b"), 100,
            indexedIds = { setOf("a", "b") },
            search = { _, _, _ -> listOf(VectorHit("a", .8f)) },
        )

        assertEquals(ChannelStatus.PARTIAL, report.status)
        assertEquals(2, report.indexedCount)
        assertEquals("VECTOR_COVERAGE_PARTIAL", report.errorCode)
    }

    @Test
    fun emptyEligibleScopeIsNotRequiredEvenWhenModelIsMissing() = runBlocking {
        val report = SemanticChannelReporter.execute(
            "dog", null, 0, emptySet(), 100,
            indexedIds = { error("must not load an index") },
            search = { _, _, _ -> error("must not search") },
        )

        assertEquals(ChannelStatus.NOT_REQUIRED, report.status)
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
    fun noResultWordingDistinguishesSearchFailureFromCompletedZeroHits() {
        fun report(status: ChannelStatus) = RetrievalChannelReport<VectorHit>(
            channel = RetrievalChannel.SEMANTIC,
            status = status,
            eligibleCount = 10,
            indexedCount = if (status == ChannelStatus.UNAVAILABLE || status == ChannelStatus.FAILED) 0 else 8,
            searchedCount = if (status == ChannelStatus.UNAVAILABLE || status == ChannelStatus.FAILED) 0 else 8,
            hits = emptyList(),
        )

        val unavailable = RetrievalCoverageWording.boundedSemanticNoResult(report(ChannelStatus.UNAVAILABLE))
        val failed = RetrievalCoverageWording.boundedSemanticNoResult(report(ChannelStatus.FAILED))
        val partial = RetrievalCoverageWording.boundedSemanticNoResult(report(ChannelStatus.PARTIAL))
        val success = RetrievalCoverageWording.boundedSemanticNoResult(report(ChannelStatus.SUCCESS))

        assertTrue(unavailable.contains("did not run"))
        assertTrue(failed.contains("failed"))
        assertTrue(partial.contains("bounded top-K retrieval pass found no supported matches"))
        assertTrue(success.contains("bounded top-K retrieval pass found no supported matches"))
        assertFalse(unavailable.contains("found no supported matches"))
        assertFalse(failed.contains("found no supported matches"))
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
