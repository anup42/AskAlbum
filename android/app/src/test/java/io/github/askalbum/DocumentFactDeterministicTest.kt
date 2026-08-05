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

    private fun hit(id: String, title: String, evidence: List<EvidenceRecord>): SearchHit =
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
            ),
            score = 1.0,
            evidence = evidence,
        )
}
