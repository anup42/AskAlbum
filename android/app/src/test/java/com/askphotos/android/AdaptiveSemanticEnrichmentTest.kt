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
    fun factDecoderKeepsSafeFactsAndDropsSensitiveOrUnallowlistedFacts() {
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
        val mixed = """Gemma result:
            ```json
            {
              "facts": [
                {"predicate":"scene","value":"beach","confidence":0.9,"applicability":"EVIDENCE_MEDIA_ONLY"},
                {"predicate":"object","value":"birthday card","confidence":0.88,"applicability":"EVIDENCE_MEDIA_ONLY"},
                {"predicate":"event","value":"birthday party","confidence":"0.91","applicability":"unknown"},
                {"predicate":"object","value":"credit card 4111111111111111","confidence":0.8,"applicability":"EVIDENCE_MEDIA_ONLY"},
                {"predicate":"occasion","value":"password hunter2","confidence":0.7,"applicability":"EVIDENCE_MEDIA_ONLY"},
                {"predicate":"identity","value":"Alice","confidence":0.9,"applicability":"EVIDENCE_MEDIA_ONLY"}
              ]
            }
            ```
        """.trimIndent()
        val facts = SemanticFactCodec.decode(job, mixed, "fixture")

        assertEquals(listOf("beach", "birthday card", "birthday party"), facts.map(SemanticFactRecord::value))
        assertEquals("occasion", facts.last().predicate)
        assertEquals("EVIDENCE_MEDIA_ONLY", facts.last().applicability)
    }

    @Test
    fun malformedModelOutputIsNotRetriedAsAnInferenceFailure() {
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
        val error = runCatching {
            SemanticFactCodec.decode(job, "not-json", "fixture")
        }.exceptionOrNull()

        assertTrue(error is SemanticEnrichmentOutputException)
        assertEquals(false, SemanticEnrichmentFailurePolicy.isRetryable(requireNotNull(error)))
        assertEquals(true, SemanticEnrichmentFailurePolicy.isRetryable(IllegalStateException("backend unavailable")))
    }

    @Test
    fun jobBudgetReservesRecentEventsBeforeExactDuplicates() {
        val duplicateItems = (0 until 140).flatMap { group ->
            listOf(
                item("duplicate-$group-a", hash = group.toLong(), time = group * 1_000L, quality = 0.8f, faces = 0),
                item("duplicate-$group-b", hash = group.toLong(), time = group * 1_000L + 1L, quality = 0.7f, faces = 0),
            )
        }
        val eventItems = (0 until 60).map { event ->
            item(
                id = "event-$event",
                hash = 10_000L + event,
                time = 1_000_000_000L + event * 100_000L,
                quality = 0.9f,
                faces = 2,
            )
        }
        val items = duplicateItems + eventItems
        val plan = AdaptiveRepresentativeSelector.buildPlan(
            items = items,
            eventMembership = eventItems.associate { it.id to it.id.removePrefix("event-").toLong() },
            embeddingReadyIds = items.mapTo(hashSetOf(), GalleryItem::id),
        )

        assertTrue(plan.jobs.size in 48..128)
        assertTrue(plan.jobs.count { it.reason == "diverse_event_representative" } >= 48)
        assertTrue(plan.jobs.count { it.reason == "exact_duplicate_canonical" } <= 12)
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
