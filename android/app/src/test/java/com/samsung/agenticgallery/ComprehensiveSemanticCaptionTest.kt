package com.samsung.agenticgallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComprehensiveSemanticCaptionTest {
    private val job = SemanticEnrichmentJobRecord(
        id = "job",
        scope = SemanticFactScope.MEDIA,
        subjectId = "media-1",
        representativeMediaId = "media-1",
        reason = "personal_event_people_representative",
        status = SemanticEnrichmentStatus.RUNNING,
        attemptCount = 1,
        userRequested = true,
    )
    private val bindings = listOf(
        PersonVerificationBinding("media-1:0", "person_me", "P1", setOf("me"), 0.1f, 0.1f, 0.2f, 0.2f),
        PersonVerificationBinding("media-1:1", "person_wife", "P2", setOf("wife"), 0.6f, 0.1f, 0.7f, 0.2f),
    )

    @Test
    fun oneResponseProducesCaptionFactsAndGeneralPersonAppearance() {
        val raw = """
            {"detailedCaption":"P1 wears a long red floral dress and black sandals beside P2 in white shoes.",
             "captionConfidence":0.95,
             "people":[
               {"personRef":"P1","visibility":"FULL_BODY","associationStatus":"CONFIDENT","bodyRegion":[0.05,0.05,0.45,0.98],
                "wornItems":[
                  {"category":"CLOTHING","itemType":"dress","colors":["red"],"pattern":"floral","style":"long","bodyRegion":"FULL_BODY","confidence":0.97,"region":[0.08,0.2,0.43,0.9]},
                  {"category":"FOOTWEAR","itemType":"sandals","colors":["black"],"bodyRegion":"FEET","confidence":0.91,"region":[0.1,0.85,0.4,0.99]}],
                "carriedItems":[],"actions":["standing"],"confidence":0.95},
               {"personRef":"P2","visibility":"FULL_BODY","associationStatus":"CONFIDENT","bodyRegion":[0.52,0.05,0.95,0.98],
                "wornItems":[{"category":"FOOTWEAR","itemType":"shoes","colors":["white"],"bodyRegion":"FEET","confidence":0.94,"region":[0.6,0.85,0.9,0.99]}],
                "carriedItems":[],"actions":[],"confidence":0.94}],
             "facts":[{"predicate":"setting","value":"decorated living room","confidence":0.9,"applicability":"EVIDENCE_MEDIA_ONLY"}]}
        """.trimIndent()

        val result = SemanticEnrichmentCodec.decode(job, raw, "fixture", bindings)

        assertTrue(requireNotNull(result.caption).text.contains("black sandals"))
        assertEquals(1, result.facts.size)
        assertEquals(setOf("dress", "sandals", "shoes"), result.personFacts.mapNotNull(PersonVisualFactRecord::itemType).toSet())
        assertEquals(BodyRegion.FEET, result.personFacts.single { it.itemType == "shoes" }.bodyRegion)
        assertEquals("person_wife", result.personFacts.single { it.itemType == "shoes" }.clusterId)
    }

    @Test
    fun ambiguousBindingDoesNotCreatePersonFacts() {
        val raw = """
            {"detailedCaption":"Two overlapping people stand indoors.","captionConfidence":0.8,
             "people":[{"personRef":"P1","visibility":"PARTIAL","associationStatus":"AMBIGUOUS",
               "wornItems":[{"category":"CLOTHING","itemType":"dress","colors":["white"],"bodyRegion":"FULL_BODY","confidence":0.9}],
               "carriedItems":[],"actions":[]}],
             "facts":[]}
        """.trimIndent()
        val result = SemanticEnrichmentCodec.decode(job, raw, "fixture", bindings)
        assertTrue(result.personFacts.isEmpty())
        assertEquals(PersonAssociationStatus.AMBIGUOUS, requireNotNull(result.caption).personRefs.single().associationStatus)
    }

    @Test
    fun sensitiveCaptionIsDroppedWithoutDroppingSafeFacts() {
        val raw = """
            {"detailedCaption":"A screen displays password hunter2.","captionConfidence":0.9,"people":[],
             "facts":[{"predicate":"scene","value":"computer screen","confidence":0.8,"applicability":"EVIDENCE_MEDIA_ONLY"}]}
        """.trimIndent()
        val result = SemanticEnrichmentCodec.decode(job, raw, "fixture", emptyList())
        assertNull(result.caption)
        assertEquals("computer screen", result.facts.single().value)
    }
}
