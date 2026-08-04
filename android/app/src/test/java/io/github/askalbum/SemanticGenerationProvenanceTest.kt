package io.github.anup42.askalbum

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticGenerationProvenanceTest {
    @Test
    fun captionChunksUseOnlyMatchingGenerationFacts() {
        val caption = SemanticCaptionRecord(
            id = "caption-a",
            scope = SemanticFactScope.MEDIA,
            subjectId = "media-a",
            text = "A family scene.",
            confidence = 0.9f,
            evidenceMediaId = "media-a",
            modelVersion = "gemma-e2b",
            promptVersion = "caption-v4",
            generationId = "generation-a",
        )
        val matching = SemanticFactRecord(
            scope = SemanticFactScope.MEDIA,
            subjectId = "media-a",
            predicate = "scene",
            value = "decorated living room",
            confidence = 0.9f,
            evidenceMediaId = "media-a",
            modelVersion = "gemma-e2b",
            promptVersion = "caption-v4",
            generationId = "generation-a",
        )
        val mismatched = matching.copy(value = "unrelated event context", generationId = "generation-b")
        val chunks = SemanticCaptionChunker.generate(caption, listOf(matching, mismatched), emptyList())

        assertTrue(chunks.any { it.exactText.contains("decorated living room") })
        assertFalse(chunks.any { it.exactText.contains("unrelated event context") })
    }

    @Test
    fun legacyCaptionDoesNotAttachUncorrelatedStructuredFacts() {
        val caption = SemanticCaptionRecord(
            id = "legacy-caption",
            scope = SemanticFactScope.MEDIA,
            subjectId = "media-a",
            text = "A family scene.",
            confidence = 0.9f,
            evidenceMediaId = "media-a",
            modelVersion = "legacy",
            promptVersion = "legacy",
        )
        val legacyFact = SemanticFactRecord(
            scope = SemanticFactScope.MEDIA,
            subjectId = "media-a",
            predicate = "scene",
            value = "unrelated event context",
            confidence = 0.9f,
            evidenceMediaId = "media-a",
            modelVersion = "legacy",
            promptVersion = "legacy",
        )

        val chunks = SemanticCaptionChunker.generate(caption, listOf(legacyFact), emptyList())

        assertFalse(chunks.any { it.exactText.contains("unrelated event context") })
        assertTrue(chunks.any { it.exactText == "A family scene." })
    }

    @Test
    fun eventCaptionCannotAttachMediaPersonFacts() {
        val caption = SemanticCaptionRecord(
            id = "event-caption",
            scope = SemanticFactScope.EVENT,
            subjectId = "event-1",
            text = "A family event.",
            confidence = 0.8f,
            evidenceMediaId = "media-a",
            modelVersion = "gemma-e2b",
            promptVersion = "caption-v4",
            generationId = "event-generation",
        )
        val eventPersonFact = PersonVisualFactRecord(
            mediaId = "media-a",
            clusterId = "me-cluster",
            personRef = "P1",
            relation = PersonVisualRelation.WEARING,
            category = WornItemCategory.CLOTHING,
            itemType = "red dress",
            value = "red dress",
            confidence = 0.9f,
            faceRegion = listOf(0.1f, 0.1f, 0.3f, 0.3f),
            modelVersion = "gemma-e2b",
            promptVersion = "caption-v4",
            generationId = "event-generation",
        )

        val chunks = SemanticCaptionChunker.generate(caption, emptyList(), listOf(eventPersonFact))

        assertTrue(chunks.none { it.clusterId == "me-cluster" })
    }

    @Test
    fun personFactsFromAnotherModelOrPromptAreIgnored() {
        val caption = SemanticCaptionRecord(
            id = "caption-a",
            scope = SemanticFactScope.MEDIA,
            subjectId = "media-a",
            text = "A family scene.",
            confidence = 0.8f,
            evidenceMediaId = "media-a",
            modelVersion = "gemma-e2b",
            promptVersion = "caption-v4",
            generationId = "generation-a",
        )
        val staleFact = PersonVisualFactRecord(
            mediaId = "media-a",
            clusterId = "me-cluster",
            personRef = "P1",
            relation = PersonVisualRelation.WEARING,
            category = WornItemCategory.CLOTHING,
            itemType = "red dress",
            value = "red dress",
            confidence = 0.9f,
            faceRegion = listOf(0.1f, 0.1f, 0.3f, 0.3f),
            modelVersion = "gemma-e4b",
            promptVersion = "caption-v3",
            generationId = "generation-a",
        )

        val chunks = SemanticCaptionChunker.generate(caption, emptyList(), listOf(staleFact))

        assertTrue(chunks.none { it.clusterId == "me-cluster" })
    }
}
