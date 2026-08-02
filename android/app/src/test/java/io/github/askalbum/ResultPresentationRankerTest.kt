package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Test

class ResultPresentationRankerTest {
    @Test
    fun exactDuplicatesCollapseToHighestQualityRepresentative() {
        val hits = listOf(
            hit("low", hash = 42, capturedAt = 1_000, quality = .2f),
            hit("best", hash = 42, capturedAt = 90_000, quality = .9f),
        )

        val collapsed = DuplicateCollapse.collapse(hits)

        assertEquals(1, collapsed.size)
        assertEquals("best", collapsed.single().item.id)
        assertEquals(listOf("low"), collapsed.single().duplicateIds)
    }

    @Test
    fun similarHashesCollapseOnlyInsideBurstWindow() {
        val close = DuplicateCollapse.collapse(
            listOf(hit("a", 0, 1_000, .5f), hit("b", 1, 10_000, .6f)),
        )
        val far = DuplicateCollapse.collapse(
            listOf(hit("a", 0, 1_000, .5f), hit("b", 1, 100_000, .6f)),
        )

        assertEquals(1, close.size)
        assertEquals(2, far.size)
    }

    @Test
    fun identicalVisualHashesDoNotCollapseDocumentsWithDifferentOcr() {
        val distinct = DuplicateCollapse.collapse(
            listOf(
                hit("wifi", hash = 42, ocrText = "Password: mango-tree-2048"),
                hit("hotel", hash = 42, ocrText = "Booking: TEST-SG-1203"),
            ),
        )
        val duplicate = DuplicateCollapse.collapse(
            listOf(
                hit("wifi-a", hash = 42, ocrText = "Password: mango-tree-2048"),
                hit("wifi-b", hash = 42, ocrText = "  password:  MANGO-TREE-2048 "),
            ),
        )

        assertEquals(listOf("wifi", "hotel"), distinct.map { it.item.id })
        assertEquals(1, duplicate.size)
    }

    @Test
    fun eventDiversitySpreadsFirstScreenWithoutDroppingResults() {
        val hits = listOf(hit("e1a"), hit("e1b"), hit("e2"), hit("e3"))
        val events = mapOf("e1a" to 1L, "e1b" to 1L, "e2" to 2L, "e3" to 3L)

        val diversified = EventDiversity.rerank(hits, events, window = 4)

        assertEquals(listOf("e1a", "e2", "e3", "e1b"), diversified.map { it.item.id })
        assertEquals(hits.map { it.item.id }.toSet(), diversified.map { it.item.id }.toSet())
    }

    private fun hit(
        id: String,
        hash: Long? = null,
        capturedAt: Long? = null,
        quality: Float? = null,
        ocrText: String = "",
    ) = SearchHit(
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
            license = "CC0",
            sourceUrl = "",
            assetPath = null,
            capturedAt = capturedAt,
            width = 100,
            height = 100,
            perceptualHash = hash,
            qualityScore = quality,
            ocrText = ocrText,
        ),
        score = 1.0,
        evidence = emptyList(),
    )
}
