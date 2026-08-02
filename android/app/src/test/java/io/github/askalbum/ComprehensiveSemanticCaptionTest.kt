package io.github.anup42.askalbum

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
            {"sceneSummary":"P1 and P2 are posing together in a decorated living room.",
             "primaryActivity":{"label":"posing together","confidence":0.95,"evidence":["P1 is standing beside P2"]},
             "actions":[{"subjectRef":"P1","action":"holding","objectRef":"a flower","confidence":0.93}],
             "interactions":[{"subjectRef":"P1","predicate":"standing beside","targetRef":"P2","confidence":0.94}],
             "occasionIndicators":[{"indicator":"party decorations","confidence":0.88}],
             "possibleOccasion":{"label":"celebration","confidence":0.72,"isDirectlyConfirmed":false},
             "detailedCaption":"P1 wears a long red floral dress and black sandals beside P2 in white shoes.",
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
        assertEquals("decorated living room", result.facts.single { it.predicate == "setting" }.value)
        assertEquals(
            setOf("dress", "sandals", "shoes"),
            result.personFacts.filter { it.relation == PersonVisualRelation.WEARING }
                .mapNotNull(PersonVisualFactRecord::itemType).toSet(),
        )
        assertEquals(BodyRegion.FEET, result.personFacts.single { it.itemType == "shoes" }.bodyRegion)
        assertEquals("person_wife", result.personFacts.single { it.itemType == "shoes" }.clusterId)
        assertTrue(requireNotNull(result.caption).text.startsWith("P1 and P2 are posing together"))
        assertEquals("posing together", result.facts.single { it.predicate == "primary_activity" }.value)
        assertEquals(
            "POSSIBLE_INFERENCE",
            result.facts.single { it.predicate == "possible_occasion" }.applicability,
        )
        assertEquals(
            "person_wife",
            result.personFacts.single { it.relation == PersonVisualRelation.STANDING_BESIDE }.targetClusterId,
        )
        assertEquals(
            "person_me",
            result.personFacts.single { it.relation == PersonVisualRelation.HOLDING }.clusterId,
        )
    }

    @Test
    fun ambiguousBindingDoesNotCreatePersonFacts() {
        val raw = """
            {"sceneSummary":"Two overlapping people are standing indoors.",
             "detailedCaption":"Two overlapping people stand indoors.","captionConfidence":0.8,
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
            {"sceneSummary":"A computer screen is visible.",
             "detailedCaption":"A screen displays password hunter2.","captionConfidence":0.9,"people":[],
             "facts":[{"predicate":"scene","value":"computer screen","confidence":0.8,"applicability":"EVIDENCE_MEDIA_ONLY"}]}
        """.trimIndent()
        val result = SemanticEnrichmentCodec.decode(job, raw, "fixture", emptyList())
        assertNull(result.caption)
        assertEquals("computer screen", result.facts.single { it.predicate == "scene" }.value)
    }

    @Test
    fun cakeDoesNotForceAnOccasionWithoutModelEvidence() {
        val raw = """
            {"sceneSummary":"A person is placing a plain cake on a kitchen counter.",
             "primaryActivity":{"label":"placing a cake on a counter","confidence":0.91,"evidence":[]},
             "actions":[],"interactions":[],"occasionIndicators":[],"possibleOccasion":null,
             "detailedCaption":"A person is placing a plain cake on a kitchen counter.",
             "captionConfidence":0.91,"people":[],"facts":[]}
        """.trimIndent()

        val result = SemanticEnrichmentCodec.decode(job, raw, "fixture", emptyList())

        assertTrue(result.facts.none { it.predicate == "possible_occasion" })
        assertEquals("placing a cake on a counter", result.facts.single { it.predicate == "primary_activity" }.value)
    }

    @Test(expected = SemanticEnrichmentOutputException::class)
    fun captionWithoutSceneSummaryIsRejectedForCorrectiveRetry() {
        SemanticEnrichmentCodec.decode(
            job,
            """{"detailedCaption":"P1 wears red.","captionConfidence":0.9,"people":[],"facts":[]}""",
            "fixture",
            emptyList(),
        )
    }
}
