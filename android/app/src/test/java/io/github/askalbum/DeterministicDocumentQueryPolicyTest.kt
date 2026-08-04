package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicDocumentQueryPolicyTest {
    @Test
    fun sumAmountUsesTheTypedAmountFieldAndCompleteHits() {
        val plan = GalleryQueryPlan(
            originalQuery = "Sum invoice amounts",
            intent = QueryIntent.SUM,
            ocrClause = OcrClause(requestedField = "amount"),
            aggregation = AggregationSpec(AggregationOperation.SUM, "amount"),
        )

        assertEquals(OcrEntityType.AMOUNT, DeterministicDocumentQueryPolicy.field(plan)?.type)
        assertTrue(DeterministicDocumentQueryPolicy.requiresCompleteHits(plan))
    }

    @Test
    fun freeFormDocumentSearchDoesNotDisableSemanticRetrieval() {
        val plan = GalleryQueryPlan(
            originalQuery = "Show my boarding pass",
            intent = QueryIntent.DOCUMENT_QA,
            ocrClause = OcrClause(query = "boarding pass"),
        )

        assertFalse(DeterministicDocumentQueryPolicy.field(plan) != null)
        assertFalse(DeterministicDocumentQueryPolicy.requiresCompleteHits(plan))
    }
}
