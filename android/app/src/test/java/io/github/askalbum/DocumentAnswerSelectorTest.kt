package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class DocumentAnswerSelectorTest {
    @Test
    fun newerAmbiguousDocumentDoesNotFallThroughToOlderFact() {
        val newer = SearchHit(item("newer"), 1.0, emptyList())
        val fact = EvidenceRecord("older:total", "older", "document_total", "INR 900", .9f)
        val older = SearchHit(item("older"), .9, listOf(fact))

        val selection = DocumentAnswerSelector.select(listOf(newer, older))

        assertSame(newer, selection?.document)
        assertNull(selection?.fact)
    }

    @Test
    fun captureTimeSortSelectsTheNewestDocumentBeforeNoFallback() {
        val older = SearchHit(
            item("older", capturedAt = 1_000L),
            1.0,
            listOf(EvidenceRecord("older:total", "older", "document_total", "INR 900", .9f)),
        )
        val newer = SearchHit(
            item("newer", capturedAt = 2_000L),
            .1,
            listOf(EvidenceRecord("newer:total", "newer", "document_total", "INR 100", .9f)),
        )

        val selection = DocumentAnswerSelector.select(
            listOf(older, newer),
            sort = SortSpec.CAPTURE_TIME_DESC,
        )

        assertSame(newer, selection?.document)
        assertEquals("INR 100", selection?.fact?.text)
    }

    private fun item(id: String, capturedAt: Long? = null) = GalleryItem(
        id = id,
        filename = "$id-receipt.png",
        title = id,
        creator = null,
        location = "",
        latitude = null,
        longitude = null,
        tags = listOf("receipt"),
        description = "",
        license = "",
        sourceUrl = "",
        assetPath = null,
        capturedAt = capturedAt,
    )
}
