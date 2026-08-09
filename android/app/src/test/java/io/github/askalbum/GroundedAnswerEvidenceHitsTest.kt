package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Test

class GroundedAnswerEvidenceHitsTest {
    @Test
    fun mergePreservesRankedAndDeterministicEvidenceForTheSameMedia() {
        val merged = GroundedAnswerEvidenceHits.merge(
            primary = listOf(hit("receipt", evidence("caption", "caption", "receipt"))),
            deterministic = listOf(
                hit("receipt", evidence("ocr", "document_total", "INR 1,248")),
                hit("other", evidence("other", "document_total", "INR 900")),
            ),
        )

        assertEquals(listOf("receipt", "other"), merged.map { it.item.id })
        assertEquals(listOf("caption", "ocr"), merged.first().evidence.map(EvidenceRecord::id))
    }

    @Test
    fun closeCitationsCarriesOnlySupplementalEvidenceReferencedByTheAnswer() {
        val closed = GroundedAnswerEvidenceHits.closeCitations(
            primary = listOf(hit("receipt", evidence("caption", "caption", "receipt"))),
            supplemental = listOf(
                hit("receipt", evidence("ocr", "document_total", "INR 1,248")),
                hit("other", evidence("other", "document_total", "INR 900")),
                hit("unused", evidence("unused", "document_total", "INR 700")),
            ),
            evidenceIds = listOf("ocr", "other"),
        )

        assertEquals(listOf("receipt", "other"), closed.map { it.item.id })
        assertEquals(listOf("caption", "ocr"), closed.first().evidence.map(EvidenceRecord::id))
    }

    private fun hit(id: String, evidence: EvidenceRecord) = SearchHit(
        item = GalleryItem(
            id = id,
            filename = "$id.jpg",
            title = id,
            creator = null,
            location = "",
            latitude = null,
            longitude = null,
            tags = emptyList(),
            description = "",
            license = "",
            sourceUrl = "",
            assetPath = null,
        ),
        score = 1.0,
        evidence = listOf(evidence),
    )

    private fun evidence(id: String, sourceField: String, text: String) = EvidenceRecord(
        id = id,
        mediaId = "receipt",
        sourceField = sourceField,
        text = text,
        confidence = .95f,
    )
}
