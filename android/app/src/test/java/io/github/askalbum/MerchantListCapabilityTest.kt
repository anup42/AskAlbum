package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MerchantListCapabilityTest {
    @Test
    fun plannerCarriesMerchantFieldIntoListPlan() {
        val plan = QueryCompiler().compile("List merchants in my recent photos")

        assertEquals(QueryIntent.LIST, plan.intent)
        assertEquals("merchant", plan.ocrClause?.requestedField)
    }

    @Test
    fun listExecutorReturnsMerchantEvidenceInsteadOfMediaTitles() {
        val item = GalleryItem(
            id = "receipt-1",
            filename = "receipt.png",
            title = "Receipt photo",
            creator = null,
            location = "",
            album = "",
            latitude = null,
            longitude = null,
            tags = emptyList(),
            description = "",
            license = "fixture",
            sourceUrl = "fixture",
            assetPath = null,
            capturedAt = null,
        )
        val hit = SearchHit(
            item = item,
            score = 1.0,
            evidence = listOf(
                EvidenceRecord(
                    id = "merchant-evidence",
                    mediaId = item.id,
                    sourceField = "document_merchant",
                    text = "Swiggy",
                    confidence = 0.99f,
                    producerVersion = "fixture",
                ),
            ),
        )
        val answer = CapabilityAnswerExecutor.execute(
            CapabilityAnswerContext(
                plan = GalleryQueryPlan(
                    originalQuery = "List merchants",
                    intent = QueryIntent.LIST,
                    ocrClause = OcrClause(requestedField = "merchant"),
                ),
                hits = listOf(hit),
                matchCount = 1,
                exactness = ResultExactness.EXACT,
                indexedEligibleCount = 1,
                totalEligibleCount = 1,
                warnings = emptyList(),
                channelReports = emptyList(),
                deterministicHits = listOf(hit),
            ),
        )

        assertTrue(answer.detail.contains("Swiggy"))
        assertTrue(answer.evidenceIds.contains("merchant-evidence"))
    }
}
