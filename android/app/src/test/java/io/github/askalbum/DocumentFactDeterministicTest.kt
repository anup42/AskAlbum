package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentFactDeterministicTest {
    @Test
    fun documentFactUsesCompleteSourceWhenRankedHitsMissTheFact() {
        val ranked = hit("ranked", "Recent photo", emptyList())
        val deterministic = hit(
            "document",
            "Older flight ticket",
            listOf(
                EvidenceRecord(
                    id = "document:flight",
                    mediaId = "document",
                    sourceField = "document_flight_number",
                    text = "AI123",
                    confidence = 0.98f,
                ),
            ),
        )
        val answer = CapabilityAnswerExecutor.execute(
            CapabilityAnswerContext(
                plan = GalleryQueryPlan(
                    originalQuery = "what is the flight number",
                    intent = QueryIntent.DOCUMENT_QA,
                    ocrClause = OcrClause(requestedField = "flight_number"),
                ),
                hits = listOf(ranked),
                matchCount = 1,
                exactness = ResultExactness.EXACT,
                indexedEligibleCount = 2,
                totalEligibleCount = 2,
                warnings = emptyList(),
                channelReports = emptyList(),
                deterministicHits = listOf(deterministic),
            ),
        )

        assertEquals("AI123", answer.headline)
        assertTrue(answer.evidenceIds.contains("document:flight"))
    }

    @Test
    fun exactDuplicateDocumentsCountOnceForSum() {
        val first = amountHit("first", "sha256-file-v1:duplicate", "INR 100")
        val duplicate = amountHit("duplicate", "sha256-file-v1:duplicate", "INR 100")
        val distinct = amountHit("distinct", "sha256-file-v1:other", "INR 30")
        val answer = CapabilityAnswerExecutor.execute(
            CapabilityAnswerContext(
                plan = GalleryQueryPlan(
                    originalQuery = "sum receipt totals",
                    intent = QueryIntent.SUM,
                    aggregation = AggregationSpec(AggregationOperation.SUM, "total"),
                ),
                hits = listOf(first, duplicate, distinct),
                matchCount = 3,
                exactness = ResultExactness.EXACT,
                indexedEligibleCount = 3,
                totalEligibleCount = 3,
                warnings = emptyList(),
                channelReports = emptyList(),
                deterministicHits = listOf(first, duplicate, distinct),
            ),
        )

        assertEquals("INR 130", answer.headline)
    }

    private fun amountHit(id: String, digest: String, amount: String): SearchHit = hit(
        id,
        "$id receipt",
        listOf(EvidenceRecord("$id:total", id, "document_total", amount, .95f)),
        digest,
    )

    private fun hit(
        id: String,
        title: String,
        evidence: List<EvidenceRecord>,
        exactDigest: String? = null,
    ): SearchHit =
        SearchHit(
            item = GalleryItem(
                id = id,
                filename = "$id.jpg",
                title = title,
                creator = "test",
                location = "",
                latitude = null,
                longitude = null,
                tags = emptyList(),
                description = "",
                license = "",
                sourceUrl = "",
                assetPath = "",
                exactContentDigest = exactDigest,
            ),
            score = 1.0,
            evidence = evidence,
        )
}
