package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RetrievalChannelEvidenceTest {
    @Test
    fun channelProjectionPreservesOnlyEvidenceFromThatChannel() {
        val hit = SearchHit(
            item = GalleryItem(
                id = "media-1",
                filename = "media-1.jpg",
                title = "media-1",
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
            evidence = listOf(
                EvidenceRecord("image", "media-1", "image_text_embedding", "image match", .8f),
                EvidenceRecord("caption", "media-1", "semantic_caption", "caption match", .8f),
                EvidenceRecord("caption-vector", "media-1", "semantic_caption_embedding", "chunk match", .8f),
                EvidenceRecord("event", "media-1", "event", "event match", .8f),
            ),
        )

        val projected = RetrievalChannelEvidence.project(hit, RetrievalChannel.CAPTION_EMBEDDING)

        assertNotNull(projected)
        assertEquals(listOf("caption-vector"), projected!!.evidence.map(EvidenceRecord::id))
    }

    @Test
    fun channelProjectionDoesNotCreateUnsupportedEvidence() {
        val hit = SearchHit(
            item = GalleryItem(
                id = "media-1",
                filename = "media-1.jpg",
                title = "media-1",
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
            evidence = listOf(EvidenceRecord("caption", "media-1", "semantic_caption", "caption", .8f)),
        )

        assertEquals(null, RetrievalChannelEvidence.project(hit, RetrievalChannel.EVENT))
    }
}
