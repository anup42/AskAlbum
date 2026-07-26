package com.samsung.agenticgallery

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

internal enum class ForegroundIndexStopReason {
    COMPLETE,
    THERMAL,
    RETRYABLE_FAILURE,
    TIME_LIMIT,
    CYCLE_LIMIT,
    CANCELLED,
}

internal data class ForegroundIndexRunLimits(
    val maxCycles: Int = 5_000,
    val maxDurationMs: Long = 5 * 60 * 60_000L + 45 * 60_000L,
) {
    init {
        require(maxCycles in 1..5_000) { "Foreground index cycle limit is out of bounds" }
        require(maxDurationMs in 10_000L..6 * 60 * 60_000L) { "Foreground index duration is out of bounds" }
    }
}

internal data class ForegroundIndexProgress(
    val cycle: Int,
    val galleryProcessed: Int,
    val embeddingsProcessed: Int,
    val galleryHasMore: Boolean,
    val embeddingsHaveMore: Boolean,
    val retryableFailures: Int,
    val permanentFailures: Int,
    val thermalStatus: Int,
)

internal data class ForegroundIndexRunResult(
    val reason: ForegroundIndexStopReason,
    val cycles: Int,
    val galleryProcessed: Int,
    val embeddingsProcessed: Int,
    val retryableFailures: Int,
    val permanentFailures: Int,
    val elapsedMs: Long,
    val thermalStatus: Int,
)

internal class ForegroundIndexCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val app = appContext as AgenticGalleryApplication
    private val repository = app.repository
    private val admissionPolicy = BackgroundWorkAdmissionPolicy(appContext)
    private val jobControls = IndexingJobControlsStore(appContext)

    suspend fun run(
        allowedMediaIds: Set<String>? = null,
        limits: ForegroundIndexRunLimits = ForegroundIndexRunLimits(),
        onProgress: (ForegroundIndexProgress) -> Unit = {},
    ): ForegroundIndexRunResult {
        require(allowedMediaIds == null || allowedMediaIds.isNotEmpty()) { "Foreground index scope is empty" }
        IndexScheduler.cancelAndWait(appContext)
        EmbeddingIndexScheduler.cancelAndWait(appContext)
        repository.recoverInterruptedJobs()
        val started = SystemClock.elapsedRealtime()
        val job = currentCoroutineContext()[Job]
        var cycles = 0
        var galleryProcessed = 0
        var embeddingsProcessed = 0
        var retryableFailures = 0
        var permanentFailures = 0

        GalleryIndexBatchProcessor(appContext, repository).use { gallery ->
            val embeddings = EmbeddingIndexBatchProcessor(
                context = appContext,
                repository = repository,
                vectors = app.services.semanticVectorStore,
                engine = app.services.embeddingEngine,
            )
            while (cycles < limits.maxCycles) {
                val before = admissionPolicy.evaluate()
                if (!before.allowed) {
                    return finish(
                        ForegroundIndexStopReason.THERMAL,
                        cycles,
                        galleryProcessed,
                        embeddingsProcessed,
                        retryableFailures,
                        permanentFailures,
                        started,
                        before.thermalStatus,
                        allowedMediaIds,
                    )
                }
                if (SystemClock.elapsedRealtime() - started >= limits.maxDurationMs) {
                    return finish(
                        ForegroundIndexStopReason.TIME_LIMIT,
                        cycles,
                        galleryProcessed,
                        embeddingsProcessed,
                        retryableFailures,
                        permanentFailures,
                        started,
                        before.thermalStatus,
                        allowedMediaIds,
                    )
                }

                val canContinue = {
                    job?.isActive != false && admissionPolicy.evaluate().allowed
                }
                val controls = jobControls.load()
                val galleryBatch = IndexingResourceCoordinator.withBackgroundPermit {
                    if (controls.mediaAnalysisEnabled) {
                        gallery.processBatch(
                            allowedMediaIds = allowedMediaIds,
                            rebuildEvents = false,
                            ownerId = "foreground-gallery",
                            canContinue = { canContinue() && jobControls.load().mediaAnalysisEnabled },
                        )
                    } else {
                        IndexBatchResult(processed = 0, hasMore = false)
                    }
                }
                val embeddingBatch = IndexingResourceCoordinator.withBackgroundPermit {
                    if (controls.embeddingsEnabled) {
                        embeddings.processBatch(
                            allowedMediaIds,
                            ownerId = "foreground-embeddings",
                            canContinue = { canContinue() && jobControls.load().embeddingsEnabled },
                        )
                    } else {
                        IndexBatchResult(processed = 0, hasMore = false)
                    }
                }
                cycles++
                galleryProcessed += galleryBatch.processed
                embeddingsProcessed += embeddingBatch.processed
                retryableFailures += galleryBatch.retryableFailures + embeddingBatch.retryableFailures
                permanentFailures += galleryBatch.permanentFailures + embeddingBatch.permanentFailures
                val after = admissionPolicy.evaluate()
                onProgress(
                    ForegroundIndexProgress(
                        cycle = cycles,
                        galleryProcessed = galleryProcessed,
                        embeddingsProcessed = embeddingsProcessed,
                        galleryHasMore = galleryBatch.hasMore,
                        embeddingsHaveMore = embeddingBatch.hasMore,
                        retryableFailures = retryableFailures,
                        permanentFailures = permanentFailures,
                        thermalStatus = after.thermalStatus,
                    ),
                )
                val reason = when {
                    !after.allowed -> ForegroundIndexStopReason.THERMAL
                    job?.isActive == false || galleryBatch.stopped || embeddingBatch.stopped -> ForegroundIndexStopReason.CANCELLED
                    !galleryBatch.hasMore && !embeddingBatch.hasMore -> ForegroundIndexStopReason.COMPLETE
                    else -> null
                }
                if (reason != null) {
                    return finish(
                        reason,
                        cycles,
                        galleryProcessed,
                        embeddingsProcessed,
                        retryableFailures,
                        permanentFailures,
                        started,
                        after.thermalStatus,
                        allowedMediaIds,
                    )
                }
            }
        }
        val thermalStatus = admissionPolicy.evaluate().thermalStatus
        return finish(
            ForegroundIndexStopReason.CYCLE_LIMIT,
            cycles,
            galleryProcessed,
            embeddingsProcessed,
            retryableFailures,
            permanentFailures,
            started,
            thermalStatus,
            allowedMediaIds,
        )
    }

    private fun finish(
        reason: ForegroundIndexStopReason,
        cycles: Int,
        galleryProcessed: Int,
        embeddingsProcessed: Int,
        retryableFailures: Int,
        permanentFailures: Int,
        started: Long,
        thermalStatus: Int,
        allowedMediaIds: Set<String>?,
    ): ForegroundIndexRunResult {
        if (galleryProcessed > 0) repository.rebuildEvents()
        if (allowedMediaIds == null && reason != ForegroundIndexStopReason.COMPLETE) {
            IndexScheduler.schedule(appContext)
            if (app.services.semanticVectorStore.producerVersion() != null) {
                EmbeddingIndexScheduler.schedule(appContext)
            }
        }
        if (allowedMediaIds == null && repository.peopleIndexStatus().enabled) {
            PeopleIndexScheduler.schedule(appContext)
        }
        return ForegroundIndexRunResult(
            reason = reason,
            cycles = cycles,
            galleryProcessed = galleryProcessed,
            embeddingsProcessed = embeddingsProcessed,
            retryableFailures = retryableFailures,
            permanentFailures = permanentFailures,
            elapsedMs = SystemClock.elapsedRealtime() - started,
            thermalStatus = thermalStatus,
        )
    }
}
