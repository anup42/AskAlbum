package com.askphotos.android

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

    private fun item(id: String) = GalleryItem(
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
    )
}
