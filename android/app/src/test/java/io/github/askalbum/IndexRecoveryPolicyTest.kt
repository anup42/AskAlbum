package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexRecoveryPolicyTest {
    @Test
    fun pipelineRecoveryOnlyIncludesItsOwnStages() {
        assertEquals(
            setOf(IndexStage.EMBEDDING),
            IndexRecoveryPolicy.mediaStages(setOf(IndexRecoveryPipeline.EMBEDDING)),
        )
        assertEquals(
            setOf(IndexStage.FACES),
            IndexRecoveryPolicy.mediaStages(setOf(IndexRecoveryPipeline.PEOPLE)),
        )
        assertEquals(
            setOf(IndexStage.THUMBNAIL, IndexStage.VIDEO_KEYFRAMES, IndexStage.OCR, IndexStage.ENRICHMENT),
            IndexRecoveryPolicy.mediaStages(setOf(IndexRecoveryPipeline.MEDIA_ANALYSIS)),
        )
    }

    @Test
    fun semanticAndCaptionRecoveryDoNotClaimMediaStages() {
        val stages = IndexRecoveryPolicy.mediaStages(
            setOf(IndexRecoveryPipeline.SEMANTIC_MEMORY, IndexRecoveryPipeline.CAPTION_EMBEDDING),
        )

        assertTrue(stages.isEmpty())
        assertFalse(IndexRecoveryPipeline.EMBEDDING in setOf(IndexRecoveryPipeline.SEMANTIC_MEMORY))
    }
}
