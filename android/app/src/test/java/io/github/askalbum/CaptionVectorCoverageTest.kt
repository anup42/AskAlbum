package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptionVectorCoverageTest {
    @Test
    fun requiredQueryWithNoCaptionChunksIsPartial() {
        assertEquals(
            ChannelStatus.PARTIAL,
            CaptionVectorCoveragePolicy.status(
                queryRequired = true,
                eligibleMediaCount = 36,
                eligibleChunkCount = 0,
                indexedChunkCount = 0,
            ),
        )
    }

    @Test
    fun emptyEligibleMediaSetIsNotRequired() {
        assertEquals(
            ChannelStatus.NOT_REQUIRED,
            CaptionVectorCoveragePolicy.status(
                queryRequired = true,
                eligibleMediaCount = 0,
                eligibleChunkCount = 0,
                indexedChunkCount = 0,
            ),
        )
    }

    @Test
    fun completeChunkCoverageIsSuccess() {
        assertEquals(
            ChannelStatus.SUCCESS,
            CaptionVectorCoveragePolicy.status(
                queryRequired = true,
                eligibleMediaCount = 12,
                eligibleChunkCount = 24,
                indexedChunkCount = 24,
            ),
        )
    }

    @Test
    fun completeChunksForOnlyPartOfEligibleMediaRemainPartial() {
        assertEquals(
            ChannelStatus.PARTIAL,
            CaptionVectorCoveragePolicy.status(
                queryRequired = true,
                eligibleMediaCount = 12,
                captionedMediaCount = 11,
                eligibleChunkCount = 24,
                indexedChunkCount = 24,
            ),
        )
    }

    @Test
    fun incompleteChunksRemainInCoverageDenominator() {
        val chunks = listOf(
            chunk("complete", CaptionEmbeddingState.COMPLETE, "pack@1"),
            chunk("pending", CaptionEmbeddingState.PENDING, null),
            chunk("failed", CaptionEmbeddingState.FAILED_EXHAUSTED, "pack@1"),
        )

        assertEquals(
            setOf("complete"),
            CaptionVectorCoveragePolicy.searchableChunkIds(chunks, "pack@1"),
        )
        assertEquals(
            ChannelStatus.PARTIAL,
            CaptionVectorCoveragePolicy.status(
                queryRequired = true,
                eligibleMediaCount = 3,
                eligibleChunkCount = chunks.size,
                indexedChunkCount = 1,
            ),
        )
    }

    private fun chunk(
        id: String,
        state: CaptionEmbeddingState,
        modelVersion: String?,
    ) = SemanticCaptionChunkRecord(
        id = id,
        captionId = "caption-$id",
        mediaId = "media-$id",
        scope = SemanticFactScope.MEDIA,
        scopeId = "media-$id",
        evidenceMediaId = "media-$id",
        clusterId = null,
        chunkType = CaptionChunkType.SCENE,
        exactText = id,
        confidence = 1f,
        applicability = "DIRECT",
        captionModelVersion = "caption-model",
        captionPromptVersion = "prompt-v1",
        chunkPolicyVersion = SemanticCaptionChunker.POLICY_VERSION,
        embeddingModelVersion = modelVersion,
        embeddingState = state,
    )
}
