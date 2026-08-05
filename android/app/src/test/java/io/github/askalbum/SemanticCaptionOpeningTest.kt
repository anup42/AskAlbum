package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SemanticCaptionOpeningTest {
    private val job = SemanticEnrichmentJobRecord(
        id = "caption-opening-job",
        scope = SemanticFactScope.MEDIA,
        subjectId = "media-1",
        representativeMediaId = "media-1",
        reason = PersonalSemanticMemoryPolicy.jobReason("fixture"),
        status = SemanticEnrichmentStatus.PENDING,
        attemptCount = 0,
        userRequested = true,
    )

    @Test
    fun paraphrasedSceneOpeningIsNotPrependedAgain() {
        val result = SemanticEnrichmentCodec.decode(
            job,
            """
            {
              "sceneSummary":"Two people are posing beside a decorated cake in a living room.",
              "activityState":"OBSERVED",
              "detailedCaption":"Two people pose next to a decorated cake in a living room. P1 is holding a knife.",
              "people":[],
              "facts":[]
            }
            """.trimIndent(),
            "fixture",
            emptyList(),
        )

        val caption = requireNotNull(result.caption).text
        assertEquals(
            "Two people pose next to a decorated cake in a living room. P1 is holding a knife.",
            caption,
        )
        assertFalse(caption.contains("Two people are posing beside a decorated cake"))
    }
}
