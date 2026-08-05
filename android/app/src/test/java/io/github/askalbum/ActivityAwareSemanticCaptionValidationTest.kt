package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityAwareSemanticCaptionValidationTest {
    private val job = SemanticEnrichmentJobRecord(
        id = "job-activity",
        scope = SemanticFactScope.MEDIA,
        subjectId = "media-1",
        representativeMediaId = "media-1",
        reason = "personal_media:fixture",
        status = SemanticEnrichmentStatus.PENDING,
        attemptCount = 0,
        userRequested = true,
    )

    private val bindings = listOf(
        PersonVerificationBinding("media-1:0", "me-cluster", "P1", setOf("me"), 0.1f, 0.1f, 0.3f, 0.9f),
        PersonVerificationBinding("media-1:1", "wife-cluster", "P2", setOf("wife"), 0.6f, 0.1f, 0.8f, 0.9f),
    )

    @Test
    fun staticImageDoesNotReceiveInventedActivity() {
        val result = SemanticEnrichmentCodec.decode(
            job,
            """
            {
              "sceneSummary":"A close-up photograph of medication packaging.",
              "imageSubject":"medication blister pack",
              "observedActivity":null,
              "activityState":"NONE_VISIBLE",
              "primaryActivity":{"label":"viewing the packaging","confidence":0.99},
              "detailedCaption":"A close-up photograph of a medication blister pack on a plain surface.",
              "facts":[]
            }
            """.trimIndent(),
            "gemma-fixture",
            emptyList(),
        )

        assertTrue(result.facts.any { it.predicate == "image_subject" && it.value == "medication blister pack" })
        assertTrue(result.facts.any { it.predicate == "activity_state" && it.value == "NONE_VISIBLE" })
        assertFalse(result.facts.any { it.predicate == "primary_activity" })
        assertFalse(result.personFacts.any { it.relation == PersonVisualRelation.ACTION })
    }

    @Test
    fun observedActionsBindToPeopleAndRejectNegativeOrNullValues() {
        val result = SemanticEnrichmentCodec.decode(
            job,
            """
            {
              "sceneSummary":"Two people are holding a gift together.",
              "imageSubject":"two-person gift scene",
              "observedActivity":"holding a gift",
              "activityState":"OBSERVED",
              "primaryActivity":{"label":"holding a gift","confidence":0.95},
              "detailedCaption":"Two people are holding a wrapped gift together.",
              "people":[
                {"personRef":"P1","visibility":"FULL_BODY","associationStatus":"CONFIDENT",
                 "bodyRegion":[0.1,0.1,0.3,0.9],"actions":["holding"],"confidence":0.95},
                {"personRef":"P2","visibility":"FULL_BODY","associationStatus":"CONFIDENT",
                 "bodyRegion":[0.6,0.1,0.8,0.9],"actions":["not holding"],"confidence":0.95}
              ],
              "actions":[{"subjectRef":"P1","action":"holding","objectRef":null,"confidence":0.95}],
              "interactions":[
                {"subjectRef":"P1","predicate":"standing beside","targetRef":"P2","confidence":0.94},
                {"subjectRef":"P1","predicate":"not standing beside","targetRef":"P2","confidence":0.94}
              ],
              "facts":[]
            }
            """.trimIndent(),
            "gemma-fixture",
            bindings,
        )

        assertTrue(result.personFacts.any { it.clusterId == "me-cluster" && it.relation == PersonVisualRelation.HOLDING })
        assertTrue(result.personFacts.any {
            it.clusterId == "me-cluster" &&
                it.relation == PersonVisualRelation.STANDING_BESIDE &&
                it.targetClusterId == "wife-cluster"
        })
        assertFalse(result.personFacts.any { it.value.contains("not standing", ignoreCase = true) })
        assertFalse(result.personFacts.any { it.value.contains("null", ignoreCase = true) })
        assertEquals(1, result.personFacts.count { it.relation == PersonVisualRelation.STANDING_BESIDE })
    }

    @Test
    fun faceOnlyActionIsStoredAsNotVisibleAndCannotConfirm() {
        val result = SemanticEnrichmentCodec.decode(
            job,
            """
            {
              "sceneSummary":"A face is visible near a gift.",
              "imageSubject":"portrait near a gift",
              "observedActivity":"holding a gift",
              "activityState":"OBSERVED",
              "detailedCaption":"A close portrait is shown near a wrapped gift.",
              "people":[
                {"personRef":"P1","visibility":"FACE_ONLY","associationStatus":"CONFIDENT","actions":["holding"]}
              ],
              "actions":[{"subjectRef":"P1","action":"holding","objectRef":"gift","confidence":0.99}],
              "interactions":[],
              "facts":[]
            }
            """.trimIndent(),
            "gemma-fixture",
            bindings,
        )

        val holding = result.personFacts.filter { it.relation == PersonVisualRelation.HOLDING }
        assertTrue(holding.isNotEmpty())
        assertTrue(holding.all { it.verdict == PersonVisualVerdict.NOT_VISIBLE })
    }
}
