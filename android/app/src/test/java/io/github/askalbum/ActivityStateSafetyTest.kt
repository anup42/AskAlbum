package io.github.anup42.askalbum

import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityStateSafetyTest {
    private val job = SemanticEnrichmentJobRecord(
        id = "activity-state-job",
        scope = SemanticFactScope.MEDIA,
        subjectId = "media-1",
        representativeMediaId = "media-1",
        reason = PersonalSemanticMemoryPolicy.jobReason("fixture"),
        status = SemanticEnrichmentStatus.PENDING,
        attemptCount = 0,
        userRequested = true,
    )

    @Test
    fun omittedActivityStateDoesNotCreateTypedActivityFacts() {
        val result = SemanticEnrichmentCodec.decode(
            job,
            """
            {
              "sceneSummary":"A person is near a bicycle outdoors.",
              "observedActivity":"riding a bicycle",
              "primaryActivity":{"label":"riding a bicycle","confidence":0.99},
              "detailedCaption":"A person is near a bicycle outdoors.",
              "people":[{"personRef":"P1","visibility":"FULL_BODY","associationStatus":"CONFIDENT","actions":["riding"]}],
              "actions":[{"subjectRef":"P1","action":"riding","objectRef":"bicycle","confidence":0.99}],
              "facts":[]
            }
            """.trimIndent(),
            "fixture",
            listOf(PersonVerificationBinding("face", "cluster", "P1", emptySet(), 0.1f, 0.1f, 0.3f, 0.8f)),
        )

        assertTrue(result.personFacts.isEmpty())
        assertTrue(result.facts.none { it.predicate in setOf("observed_activity", "primary_activity", "activity_indicator") })
        assertTrue(result.facts.any { it.predicate == "scene_summary" })
    }
}
