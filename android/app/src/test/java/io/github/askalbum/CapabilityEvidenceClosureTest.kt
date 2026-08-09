package io.github.anup42.askalbum

import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityEvidenceClosureTest {
    @Test
    fun deterministicListSourceIsReturnedAsEvidence() {
        val ranked = hit("ranked", "Ranked", "Other", "ranked-evidence")
        val deterministic = hit("deterministic", "Deterministic", "Goa", "deterministic-evidence")
        val plan = GalleryQueryPlan(
            originalQuery = "list places",
            intent = QueryIntent.LIST,
            grouping = Grouping.PLACE,
        )

        val answer = CapabilityAnswerExecutor.execute(
            CapabilityAnswerContext(
                plan = plan,
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

        assertTrue(answer.detail.contains("Goa"))
        assertTrue(answer.evidenceIds.contains("deterministic-evidence"))
    }

    private fun hit(id: String, title: String, location: String, evidenceId: String): SearchHit =
        SearchHit(
            item = GalleryItem(
                id = id,
                filename = "$id.jpg",
                title = title,
                creator = "test",
                location = location,
                latitude = null,
                longitude = null,
                tags = emptyList(),
                description = "",
                license = "",
                sourceUrl = "",
                assetPath = "",
            ),
            score = 1.0,
            evidence = listOf(
                EvidenceRecord(
                    id = evidenceId,
                    mediaId = id,
                    sourceField = "location",
                    text = location,
                    confidence = 0.99f,
                ),
            ),
        )
}
