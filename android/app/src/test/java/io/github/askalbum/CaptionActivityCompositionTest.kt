package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionActivityCompositionTest {
    @Test
    fun equivalentSceneActivityOpeningIsNotRepeated() {
        val result = SemanticEnrichmentCodec.decode(
            job = SemanticEnrichmentJobRecord(
                id = "job-1",
                scope = SemanticFactScope.MEDIA,
                subjectId = "media-1",
                representativeMediaId = "media-1",
                reason = "personal-caption",
                status = SemanticEnrichmentStatus.RUNNING,
                attemptCount = 1,
                userRequested = true,
            ),
            raw = """
                {
                  "sceneSummary":"Two people are cutting a cake in a room.",
                  "detailedCaption":"Two people cut the cake indoors. A table and balloons are visible.",
                  "captionConfidence":0.9,
                  "facts":[]
                }
            """.trimIndent(),
            modelVersion = "gemma-e2b",
            bindings = emptyList(),
        )

        val caption = result.caption?.text ?: error("caption missing")
        assertEquals(1, caption.lowercase().windowed("two people".length).count { it == "two people" })
        assertTrue(caption.contains("A table and balloons are visible."))
    }
}
