package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexingReliabilityPolicyTest {
    @Test
    fun retryableItemIsQuarantinedAfterThreeAttempts() {
        assertEquals(StageStatus.FAILED_RETRYABLE, IndexingRetryPolicy.failedStatus(false, 1))
        assertEquals(StageStatus.FAILED_RETRYABLE, IndexingRetryPolicy.failedStatus(false, 2))
        assertEquals(StageStatus.FAILED_EXHAUSTED, IndexingRetryPolicy.failedStatus(false, 3))
        assertEquals(StageStatus.FAILED_PERMANENT, IndexingRetryPolicy.failedStatus(true, 1))
    }

    @Test
    fun mixedSuccessDoesNotBackoffWholeWorker() {
        assertFalse(IndexingWorkerResultPolicy.shouldRetryWorker(22, 2, false, true, true))
        assertTrue(IndexingWorkerResultPolicy.shouldRetryWorker(0, 2, false, true, true))
        assertTrue(IndexingWorkerResultPolicy.shouldRetryWorker(22, 0, false, false, true))
    }

    @Test
    fun retryDelayIsBoundedAndIncreasing() {
        assertEquals(30_000L, IndexingRetryPolicy.retryDelayMillis(1))
        assertEquals(60_000L, IndexingRetryPolicy.retryDelayMillis(2))
        assertEquals(120_000L, IndexingRetryPolicy.retryDelayMillis(3))
    }

    @Test
    fun expiredLeaseRecoveryIsScopedToTheOwningPipeline() {
        assertEquals(
            setOf(IndexStage.THUMBNAIL, IndexStage.OCR, IndexStage.EVENTS, IndexStage.ENRICHMENT),
            IndexingRecoveryPolicy.stagesFor(IndexingPipeline.MEDIA_ANALYSIS),
        )
        assertEquals(
            setOf(IndexStage.EMBEDDING),
            IndexingRecoveryPolicy.stagesFor(IndexingPipeline.EMBEDDINGS),
        )
        assertEquals(
            setOf(IndexStage.FACES),
            IndexingRecoveryPolicy.stagesFor(IndexingPipeline.PEOPLE),
        )
        assertTrue(IndexStage.FACES in IndexingRecoveryPolicy.stagesFor(IndexingPipeline.ALL))
        assertTrue(IndexingRecoveryPolicy.recoversSemanticMemory(IndexingPipeline.SEMANTIC_MEMORY))
        assertFalse(IndexingRecoveryPolicy.recoversSemanticMemory(IndexingPipeline.EMBEDDINGS))
        assertTrue(IndexingRecoveryPolicy.recoversCaptionEmbeddings(IndexingPipeline.CAPTION_EMBEDDINGS))
        assertFalse(IndexingRecoveryPolicy.recoversCaptionEmbeddings(IndexingPipeline.MEDIA_ANALYSIS))
    }

    @Test
    fun everyUiIndexingJobMapsToOnlyItsOwnRecoveryPipeline() {
        assertEquals(IndexingPipeline.MEDIA_ANALYSIS, IndexingRecoveryPolicy.pipelineFor(IndexingJob.MEDIA_ANALYSIS))
        assertEquals(IndexingPipeline.EMBEDDINGS, IndexingRecoveryPolicy.pipelineFor(IndexingJob.EMBEDDINGS))
        assertEquals(IndexingPipeline.CAPTION_EMBEDDINGS, IndexingRecoveryPolicy.pipelineFor(IndexingJob.CAPTION_EMBEDDINGS))
        assertEquals(IndexingPipeline.PEOPLE, IndexingRecoveryPolicy.pipelineFor(IndexingJob.PEOPLE))
        assertEquals(IndexingPipeline.SEMANTIC_MEMORY, IndexingRecoveryPolicy.pipelineFor(IndexingJob.SEMANTIC_MEMORY))
    }
}
