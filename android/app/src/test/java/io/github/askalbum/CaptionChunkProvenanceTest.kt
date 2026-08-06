package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionChunkProvenanceTest {
    @Test
    fun onlyMatchingMediaGenerationFactsAreUsed() {
        val caption = caption()
        val valid = fact(generationId = "g1")
        val wrongGeneration = fact(generationId = "g2", value = "wrong generation")
        val wrongScope = fact(generationId = "g1", scope = SemanticFactScope.EVENT, value = "wrong scope")
        val wrongModel = fact(generationId = "g1", modelVersion = "model-2", value = "wrong model")

        assertEquals(
            listOf(valid),
            CaptionChunkFactProvenancePolicy.matchingFacts(
                caption,
                listOf(valid, wrongGeneration, wrongScope, wrongModel),
            ),
        )
    }

    @Test
    fun legacyAndContextCaptionsDoNotInheritPersonFacts() {
        val mediaFact = personFact("g1")
        val staleBodyFact = personFact("g1", bodyRegionVersion = "person-body-regions-v0")
        val mediaCaption = caption()
        val legacyCaption = mediaCaption.copy(generationId = null)
        val eventCaption = mediaCaption.copy(scope = SemanticFactScope.EVENT, subjectId = "event-1")

        assertEquals(emptyList<PersonVisualFactRecord>(), CaptionChunkFactProvenancePolicy.matchingPersonFacts(legacyCaption, listOf(mediaFact)))
        assertEquals(emptyList<PersonVisualFactRecord>(), CaptionChunkFactProvenancePolicy.matchingPersonFacts(eventCaption, listOf(mediaFact)))
        assertEquals(listOf(mediaFact), CaptionChunkFactProvenancePolicy.matchingPersonFacts(mediaCaption, listOf(mediaFact, staleBodyFact)))
    }

    @Test
    fun searchRejectsChunkFromDifferentScopeOrGeneration() {
        val caption = caption()
        val valid = chunk(caption)
        assertTrue(CaptionChunkSearchPolicy.matchesCaption(caption, valid))
        assertTrue(
            !CaptionChunkSearchPolicy.matchesCaption(
                caption,
                valid.copy(scope = SemanticFactScope.EVENT, scopeId = "event-1"),
            ),
        )
        assertTrue(!CaptionChunkSearchPolicy.matchesCaption(caption, valid.copy(generationId = "g2")))
        assertTrue(!CaptionChunkSearchPolicy.matchesCaption(caption, valid.copy(captionPromptVersion = "prompt-2")))
    }

    @Test
    fun vectorSearchRequiresCurrentCompleteChunk() {
        val caption = caption()
        val valid = chunk(caption).copy(
            embeddingModelVersion = "siglip-v1",
            embeddingState = CaptionEmbeddingState.COMPLETE,
        )
        assertTrue(CaptionChunkSearchPolicy.isSearchableVector(caption, valid))
        assertTrue(
            !CaptionChunkSearchPolicy.isSearchableVector(
                caption,
                valid.copy(chunkPolicyVersion = "caption-chunks-old"),
            ),
        )
        assertTrue(
            !CaptionChunkSearchPolicy.isSearchableVector(
                caption,
                valid.copy(embeddingState = CaptionEmbeddingState.PENDING),
            ),
        )
    }

    private fun caption() = SemanticCaptionRecord(
        id = "caption-1",
        scope = SemanticFactScope.MEDIA,
        subjectId = "media-1",
        text = "A person is holding a gift.",
        confidence = .9f,
        evidenceMediaId = "media-1",
        modelVersion = "model-1",
        promptVersion = "prompt-1",
        generationId = "g1",
    )

    private fun fact(
        generationId: String,
        scope: SemanticFactScope = SemanticFactScope.MEDIA,
        modelVersion: String = "model-1",
        value: String = "holding a gift",
    ) = SemanticFactRecord(
        scope = scope,
        subjectId = "media-1",
        predicate = "activity",
        value = value,
        confidence = .9f,
        evidenceMediaId = "media-1",
        modelVersion = modelVersion,
        promptVersion = "prompt-1",
        generationId = generationId,
    )

    private fun personFact(
        generationId: String,
        bodyRegionVersion: String = PersonalSemanticMemoryPolicy.BODY_REGION_VERSION,
    ) = PersonVisualFactRecord(
        mediaId = "media-1",
        clusterId = "me-cluster",
        personRef = "P1",
        relation = PersonVisualRelation.HOLDING,
        itemType = "gift",
        value = "holding gift",
        confidence = .9f,
        faceRegion = listOf(.1f, .1f, .2f, .2f),
        modelVersion = "model-1",
        promptVersion = "prompt-1",
        bodyRegionVersion = bodyRegionVersion,
        generationId = generationId,
    )

    private fun chunk(caption: SemanticCaptionRecord) = SemanticCaptionChunkRecord(
        id = "chunk-1",
        captionId = caption.id,
        mediaId = caption.evidenceMediaId,
        scope = caption.scope,
        scopeId = caption.subjectId,
        evidenceMediaId = caption.evidenceMediaId,
        clusterId = null,
        chunkType = CaptionChunkType.SCENE,
        exactText = caption.text,
        confidence = caption.confidence,
        applicability = caption.applicability,
        captionModelVersion = caption.modelVersion,
        captionPromptVersion = caption.promptVersion,
        chunkPolicyVersion = SemanticCaptionChunker.POLICY_VERSION,
        embeddingModelVersion = null,
        embeddingState = CaptionEmbeddingState.PENDING,
        generationId = caption.generationId,
    )
}
