package io.github.anup42.askalbum

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object IndexingRetryPolicy {
    const val MAX_ITEM_ATTEMPTS = 3
    const val LEASE_MILLIS = 2L * 60L * 1_000L

    fun nextAttemptAt(nowMillis: Long, attemptCount: Int): Long =
        nowMillis + retryDelayMillis(attemptCount)

    fun retryDelayMillis(attemptCount: Int): Long {
        val exponent = (attemptCount - 1).coerceIn(0, MAX_ITEM_ATTEMPTS - 1)
        return (30_000L shl exponent).coerceAtMost(5L * 60L * 1_000L)
    }

    fun failedStatus(permanent: Boolean, attemptCount: Int): StageStatus = when {
        permanent -> StageStatus.FAILED_PERMANENT
        attemptCount >= MAX_ITEM_ATTEMPTS -> StageStatus.FAILED_EXHAUSTED
        else -> StageStatus.FAILED_RETRYABLE
    }
}

internal object IndexingRecoveryPolicy {
    val mediaAnalysisStages: Set<IndexStage> = setOf(
        IndexStage.THUMBNAIL,
        IndexStage.OCR,
        IndexStage.EVENTS,
        IndexStage.ENRICHMENT,
    )

    fun stagesFor(pipeline: IndexingPipeline): Set<IndexStage> = when (pipeline) {
        IndexingPipeline.MEDIA_ANALYSIS -> mediaAnalysisStages
        IndexingPipeline.EMBEDDINGS -> setOf(IndexStage.EMBEDDING)
        IndexingPipeline.PEOPLE -> setOf(IndexStage.FACES)
        IndexingPipeline.SEMANTIC_MEMORY,
        IndexingPipeline.CAPTION_EMBEDDINGS,
        -> emptySet()
        IndexingPipeline.ALL -> mediaAnalysisStages + setOf(IndexStage.EMBEDDING, IndexStage.FACES)
    }

    fun pipelineFor(job: IndexingJob): IndexingPipeline = when (job) {
        IndexingJob.MEDIA_ANALYSIS -> IndexingPipeline.MEDIA_ANALYSIS
        IndexingJob.EMBEDDINGS -> IndexingPipeline.EMBEDDINGS
        IndexingJob.PEOPLE -> IndexingPipeline.PEOPLE
        IndexingJob.SEMANTIC_MEMORY -> IndexingPipeline.SEMANTIC_MEMORY
        IndexingJob.CAPTION_EMBEDDINGS -> IndexingPipeline.CAPTION_EMBEDDINGS
    }

    fun recoversSemanticMemory(pipeline: IndexingPipeline): Boolean =
        pipeline == IndexingPipeline.SEMANTIC_MEMORY || pipeline == IndexingPipeline.ALL

    fun recoversCaptionEmbeddings(pipeline: IndexingPipeline): Boolean =
        pipeline == IndexingPipeline.CAPTION_EMBEDDINGS || pipeline == IndexingPipeline.ALL
}

internal object IndexingWorkerResultPolicy {
    fun shouldRetryWorker(
        processed: Int,
        retryableFailures: Int,
        stopped: Boolean,
        admissionAllowed: Boolean,
        hasImmediateWork: Boolean,
        unavailable: Boolean = false,
    ): Boolean =
        unavailable ||
            stopped ||
            !admissionAllowed ||
            (processed == 0 && retryableFailures > 0 && hasImmediateWork)
}

internal object ForegroundIndexLanePolicy {
    fun shouldDeferBackgroundWorker(foregroundActive: Boolean): Boolean = foregroundActive
}

internal object ForegroundIndexHandoffPolicy {
    fun shouldScheduleRecovery(explicitUserStop: Boolean, importJobActive: Boolean): Boolean =
        importJobActive && !explicitUserStop
}

internal object IndexingSupervisorPolicy {
    fun shouldScheduleBackgroundWork(
        foregroundActive: Boolean,
        pausedByUser: Boolean,
    ): Boolean = !foregroundActive && !pausedByUser
}

internal object IndexingRecoveryAdmissionPolicy {
    fun shouldReclaimOrphanedLeases(
        foregroundActive: Boolean,
        scheduledWorkActive: Boolean,
    ): Boolean = !foregroundActive && !scheduledWorkActive
}

internal object IndexingResourceCoordinator {
    private val backgroundInference = Mutex()
    private val interactiveQueries = java.util.concurrent.atomic.AtomicInteger()

    fun beginInteractiveQuery() {
        interactiveQueries.incrementAndGet()
    }

    fun endInteractiveQuery() {
        interactiveQueries.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
    }

    suspend fun <T> withBackgroundPermit(block: suspend () -> T): T =
        backgroundInference.withLock {
            while (interactiveQueries.get() > 0) {
                kotlinx.coroutines.delay(INTERACTIVE_POLL_MS)
            }
            block()
        }

    private const val INTERACTIVE_POLL_MS = 100L
}

internal object ForegroundIndexRuntime {
    @Volatile
    var active: Boolean = false
        private set

    fun started() {
        active = true
    }

    fun stopped() {
        active = false
    }
}
