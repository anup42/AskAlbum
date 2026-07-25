package com.askphotos.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveSemanticEnrichmentTest {
    @Test
    fun exactDuplicatesShareOneCanonicalRepresentativeAndEventsStayDiverse() {
        val items = listOf(
            item("a", hash = 7L, time = 1_000L, quality = 0.9f, faces = 1),
            item("b", hash = 7L, time = 2_000L, quality = 0.8f, faces = 1),
            item("c", hash = 99L, time = 90_000_000L, quality = 0.7f, faces = 3, ocr = "menu"),
            item("d", hash = 101L, time = 180_000_000L, quality = 0.6f, faces = 0),
        )
        val plan = AdaptiveRepresentativeSelector.buildPlan(
            items = items,
            eventMembership = mapOf("a" to 1L, "c" to 1L, "d" to 1L),
            embeddingReadyIds = items.mapTo(hashSetOf(), GalleryItem::id),
        )

        val exact = plan.groups.single { it.kind == "EXACT_DUPLICATE" }
        assertEquals(setOf("a", "b"), exact.members.toSet())
        assertEquals(1, exact.representatives.size)
        assertTrue(plan.eventRepresentatives.map { it.mediaId }.distinct().size >= 2)
        assertTrue(plan.jobs.size < items.size * 4)
    }

    @Test
    fun factDecoderRejectsSensitiveOrUnallowlistedOutput() {
        val job = SemanticEnrichmentJobRecord(
            id = "j",
            scope = SemanticFactScope.MEDIA,
            subjectId = "a",
            representativeMediaId = "a",
            reason = "test",
            status = SemanticEnrichmentStatus.RUNNING,
            attemptCount = 1,
            userRequested = false,
        )
        val safe = """{"facts":[{"predicate":"scene","value":"beach","confidence":0.9,"applicability":"EVIDENCE_MEDIA_ONLY"}]}"""
        val facts = SemanticFactCodec.decode(job, safe, "fixture")
        assertEquals("beach", facts.single().value)
    }

    private fun item(
        id: String,
        hash: Long,
        time: Long,
        quality: Float,
        faces: Int,
        ocr: String = "",
    ) = GalleryItem(
        id = id,
        filename = "$id.jpg",
        title = id,
        creator = null,
        location = "Goa",
        latitude = null,
        longitude = null,
        tags = emptyList(),
        description = "",
        license = "",
        sourceUrl = "",
        assetPath = "sample/$id.jpg",
        capturedAt = time,
        ocrText = ocr,
        faceCount = faces,
        perceptualHash = hash,
        qualityScore = quality,
    )
}
