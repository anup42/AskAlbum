package com.samsung.agenticgallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionEmbeddingRetrievalTest {
    private val caption = SemanticCaptionRecord(
        id = "caption-1",
        scope = SemanticFactScope.MEDIA,
        subjectId = "media-1",
        text = "A family gathers in a decorated living room for a birthday celebration. " +
            "Warm lamps illuminate a cake on the table. A small green toy automobile is visible " +
            "behind the sofa near the far window.",
        confidence = 0.94f,
        evidenceMediaId = "media-1",
        modelVersion = "gemma-e2b",
        promptVersion = "caption-v3",
    )

    @Test
    fun longCaptionProducesFocusedSentenceBoundedChunks() {
        val chunks = SemanticCaptionChunker.generate(
            caption = caption,
            facts = listOf(
                semanticFact("scene.setting", "decorated living room"),
                semanticFact("scene.occasion", "birthday celebration"),
                semanticFact("object", "birthday cake on a table"),
            ),
            personFacts = emptyList(),
        )

        assertTrue(chunks.size > 1)
        assertTrue(chunks.any { it.chunkType == CaptionChunkType.SCENE })
        assertTrue(chunks.any { it.chunkType == CaptionChunkType.OCCASION })
        assertTrue(chunks.any { it.exactText.contains("toy automobile", ignoreCase = true) })
        assertTrue(chunks.all { it.exactText.length <= 360 })
    }

    @Test
    fun personAppearanceChunkRetainsReviewedClusterId() {
        val chunks = SemanticCaptionChunker.generate(
            caption = caption,
            facts = emptyList(),
            personFacts = listOf(
                PersonVisualFactRecord(
                    id = "person-fact-1",
                    mediaId = "media-1",
                    clusterId = "wife-cluster",
                    personRef = "P2",
                    relation = PersonVisualRelation.WEARING,
                    category = WornItemCategory.CLOTHING,
                    itemType = "dress",
                    value = "white dress",
                    attributes = mapOf("colors" to listOf("white")),
                    bodyRegion = BodyRegion.FULL_BODY,
                    confidence = 0.96f,
                    faceRegion = listOf(0.5f, 0.1f, 0.7f, 0.3f),
                    modelVersion = "gemma-e2b",
                    promptVersion = "caption-v3",
                ),
            ),
        )

        val personChunk = chunks.single { it.chunkType == CaptionChunkType.PERSON_APPEARANCE }
        assertEquals("wife-cluster", personChunk.clusterId)
        assertFalse(personChunk.exactText.contains("wife", ignoreCase = true))
        assertTrue(personChunk.exactText.contains("white dress", ignoreCase = true))
    }

    @Test
    fun sensitiveCaptionNeverProducesChunks() {
        val protectedCaption = caption.copy(text = "The Wi-Fi password is hunter2.")
        assertTrue(
            SemanticCaptionChunker.generate(protectedCaption, emptyList(), emptyList()).isEmpty(),
        )
    }

    @Test
    fun queryVariantsStayIndependentAndMultilingual() {
        val variants = CaptionLexicalQueryBuilder.variants(
            listOf(
                "Show birthday pictures with my wife",
                "मेरी पत्नी के साथ जन्मदिन की तस्वीरें दिखाओ",
                "Wife ke saath birthday wali photos dikhao",
            ),
        )

        assertEquals(3, variants.size)
        assertTrue(variants.any { it.contains("जन्मदिन") })
        assertTrue(variants.any { it.contains("birthday", ignoreCase = true) })
        assertTrue(variants.all { CaptionLexicalQueryBuilder.ftsExpression(it) != null })
    }

    @Test
    fun contextualChunksRemainCandidateOnly() {
        val chunks = SemanticCaptionChunker.generate(
            caption.copy(
                id = "event-caption",
                scope = SemanticFactScope.EVENT,
                subjectId = "event-7",
                applicability = "CONTEXT_ONLY",
            ),
            emptyList(),
            emptyList(),
        )

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.scope == SemanticFactScope.EVENT })
        assertTrue(chunks.all { it.applicability == "CONTEXT_ONLY" })
    }

    private fun semanticFact(predicate: String, value: String) = SemanticFactRecord(
        scope = SemanticFactScope.MEDIA,
        subjectId = "media-1",
        predicate = predicate,
        value = value,
        confidence = 0.92f,
        evidenceMediaId = "media-1",
        modelVersion = "gemma-e2b",
        promptVersion = "caption-v3",
    )
}
