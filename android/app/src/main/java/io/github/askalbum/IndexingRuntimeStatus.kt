package io.github.anup42.askalbum

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

enum class IndexingPipelineState {
    RUNNING,
    WAITING_CONSTRAINTS,
    UNAVAILABLE,
    BACKOFF,
    STOPPED_BY_USER,
    PAUSED_BY_USER,
    COMPLETE,
    DEGRADED,
    FAILED,
}

internal data class IndexingWorkProgress(
    val lastProgressAt: Long?,
    val nextAttemptAt: Long?,
    val delayedRetryCount: Int,
    val quarantinedCount: Int,
    val ratePerMinute: Double? = null,
    val etaMillis: Long? = null,
) {
    companion object {
        fun from(data: androidx.work.Data?): IndexingWorkProgress {
            val lastProgressAt = data?.getLong("last_progress_at", 0L)?.takeIf { it > 0L }
            val nextAttemptAt = data?.getLong("next_attempt_at", 0L)?.takeIf { it > 0L }
            val retryableFailures = data?.getInt("retryable_failures", 0) ?: 0
            val delayedRetryCount = data?.getInt("delayed_retries", retryableFailures) ?: retryableFailures
            return IndexingWorkProgress(
                lastProgressAt = lastProgressAt,
                nextAttemptAt = nextAttemptAt,
                delayedRetryCount = delayedRetryCount,
                quarantinedCount = data?.getInt("quarantined", 0) ?: 0,
                ratePerMinute = data?.getDouble("rate_per_minute", 0.0)?.takeIf { it > 0.0 },
                etaMillis = data?.getLong("eta_millis", 0L)?.takeIf { it > 0L },
            )
        }
    }
}

internal object IndexingCoverageMath {
    fun peopleCompleted(faceScanned: Int, faceEligible: Int): Int =
        faceScanned.coerceIn(0, faceEligible.coerceAtLeast(0))

    fun peoplePending(pending: Int, faceEligible: Int): Int =
        pending.coerceIn(0, faceEligible.coerceAtLeast(0))

    fun peopleFailed(faceScanned: Int, pending: Int, faceEligible: Int): Int =
        (faceEligible - peopleCompleted(faceScanned, faceEligible) - peoplePending(pending, faceEligible))
            .coerceAtLeast(0)
}

data class IndexingPipelineSnapshot(
    val job: IndexingJob,
    val state: IndexingPipelineState,
    val completedCount: Int,
    val eligibleCount: Int,
    val inFlightCount: Int = 0,
    val delayedRetryCount: Int = 0,
    val quarantinedCount: Int = 0,
    val lastProgressAt: Long? = null,
    val nextAttemptAt: Long? = null,
    val ratePerMinute: Double? = null,
    val etaMillis: Long? = null,
    val stopReason: Int? = null,
    val errorCode: String? = null,
    val message: String,
)

internal class IndexingRuntimeStatusReader(context: Context) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)

    fun read(
        summary: IndexSummary,
        people: PeopleIndexStatus,
        semantic: SemanticMemoryProgress,
        controls: IndexingJobControls,
        admission: BackgroundWorkAdmission,
    ): Map<IndexingJob, IndexingPipelineSnapshot> {
        val media = workState("gallery-index")
        val embeddings = workState("gallery-image-embeddings")
        val captionEmbeddings = workState("gallery-caption-embeddings")
        val peopleWork = workState("gallery-people-index")
        val semanticWork = workState("semantic-enrichment")
        val retrievalAvailable = (appContext as AskAlbumApplication)
            .services.retrievalModelPackManager.current() != null
        val mediaCompleted = (summary.discovered - summary.pending - summary.failed).coerceAtLeast(0)
        val peopleCompleted = IndexingCoverageMath.peopleCompleted(summary.facesScanned, summary.faceEligible)
        val peoplePending = IndexingCoverageMath.peoplePending(people.pendingMediaCount, summary.faceEligible)
        val peopleFailed = IndexingCoverageMath.peopleFailed(
            summary.facesScanned,
            people.pendingMediaCount,
            summary.faceEligible,
        )
        val captionBackfillPending = (semantic.captionCount - semantic.captionChunkCount).coerceAtLeast(0)
        val captionPending = semantic.pendingCaptionChunkCount + semantic.runningCaptionChunkCount + captionBackfillPending
        val captionEligible = semantic.captionChunkCount + captionBackfillPending
        return mapOf(
            IndexingJob.MEDIA_ANALYSIS to snapshot(
                IndexingJob.MEDIA_ANALYSIS,
                controls.mediaAnalysisEnabled,
                summary.pending,
                summary.failed,
                mediaCompleted,
                summary.discovered,
                media,
                admission,
                foregroundActive = ForegroundIndexRuntime.active,
                pausedByUser = controls.foregroundPaused,
            ),
            IndexingJob.EMBEDDINGS to snapshot(
                IndexingJob.EMBEDDINGS,
                controls.embeddingsEnabled,
                summary.siglipVectorsPending,
                summary.siglipVectorsFailed,
                summary.siglipVectorsReady,
                summary.discovered,
                embeddings,
                admission,
                foregroundActive = ForegroundIndexRuntime.active,
                pausedByUser = controls.foregroundPaused,
                unavailable = controls.embeddingsEnabled && !retrievalAvailable && summary.discovered > 0,
                errorCode = if (controls.embeddingsEnabled && !retrievalAvailable && summary.discovered > 0) {
                    "NO_VERIFIED_RETRIEVAL_PACK"
                } else {
                    null
                },
            ),
            IndexingJob.CAPTION_EMBEDDINGS to snapshot(
                IndexingJob.CAPTION_EMBEDDINGS,
                controls.captionEmbeddingsEnabled,
                captionPending,
                semantic.failedCaptionChunkCount,
                semantic.embeddedCaptionChunkCount,
                captionEligible,
                captionEmbeddings,
                admission,
                pausedByUser = controls.foregroundPaused,
            ),
            IndexingJob.PEOPLE to snapshot(
                IndexingJob.PEOPLE,
                controls.peopleEnabled && people.enabled,
                peoplePending,
                peopleFailed,
                peopleCompleted,
                summary.faceEligible,
                peopleWork,
                admission,
                pausedByUser = controls.foregroundPaused,
            ),
            IndexingJob.SEMANTIC_MEMORY to snapshot(
                IndexingJob.SEMANTIC_MEMORY,
                controls.semanticMemoryEnabled,
                semantic.pendingJobs + semantic.runningJobs,
                semantic.failedJobs,
                semantic.completedJobs,
                semantic.totalJobs,
                semanticWork,
                admission,
                pausedByUser = controls.foregroundPaused,
            ),
        )
    }

    private fun snapshot(
        job: IndexingJob,
        enabled: Boolean,
        pending: Int,
        failed: Int,
        completed: Int,
        eligible: Int,
        work: WorkState,
        admission: BackgroundWorkAdmission,
        foregroundActive: Boolean = false,
        pausedByUser: Boolean = false,
        unavailable: Boolean = false,
        errorCode: String? = null,
    ): IndexingPipelineSnapshot {
        val state = IndexingRuntimeStatePolicy.resolve(
            enabled = enabled,
            pending = pending,
            failed = failed,
            admissionAllowed = admission.allowed,
            workRunning = work.running,
            workEnqueued = work.enqueued,
            runAttemptCount = work.runAttemptCount,
            foregroundActive = foregroundActive,
            pausedByUser = pausedByUser,
            unavailable = unavailable,
        )
        val message = when (state) {
            IndexingPipelineState.RUNNING -> "$completed / $eligible indexed"
            IndexingPipelineState.WAITING_CONSTRAINTS -> admission.reason ?: "Queued for the next available run"
            IndexingPipelineState.UNAVAILABLE -> "Verified retrieval pack unavailable"
            IndexingPipelineState.BACKOFF -> "Retry backoff after ${work.runAttemptCount} worker attempts"
            IndexingPipelineState.STOPPED_BY_USER -> "Stopped"
            IndexingPipelineState.PAUSED_BY_USER -> "Paused by user"
            IndexingPipelineState.COMPLETE -> "$completed / $eligible complete"
            IndexingPipelineState.DEGRADED -> "$completed complete; $failed quarantined"
            IndexingPipelineState.FAILED -> "$pending pending with no active worker"
        }
        return IndexingPipelineSnapshot(
            job = job,
            state = state,
            completedCount = completed,
            eligibleCount = eligible,
            inFlightCount = if (work.running) work.progressInFlight else 0,
            delayedRetryCount = work.delayedRetryCount,
            quarantinedCount = work.quarantinedCount,
            lastProgressAt = work.lastProgressAt,
            nextAttemptAt = work.nextAttemptAt,
            ratePerMinute = work.ratePerMinute,
            etaMillis = work.etaMillis,
            stopReason = work.stopReason,
            errorCode = errorCode,
            message = message,
        )
    }

    private fun workState(name: String): WorkState = runCatching {
        val infos = workManager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS)
        val unfinished = infos.filterNot { it.state.isFinished }
        val current = unfinished.firstOrNull { it.state == WorkInfo.State.RUNNING }
            ?: unfinished.firstOrNull()
            ?: infos.maxByOrNull { it.runAttemptCount }
        if (current == null) WorkState.EMPTY else WorkState(
            running = current.state == WorkInfo.State.RUNNING,
            enqueued = current.state == WorkInfo.State.ENQUEUED || current.state == WorkInfo.State.BLOCKED,
            runAttemptCount = current.runAttemptCount,
            stopReason = current.stopReason.takeIf { it != WorkInfo.STOP_REASON_NOT_STOPPED },
            progressInFlight = current.progress.getInt("in_flight", 0),
            progress = IndexingWorkProgress.from(current.progress),
        )
    }.getOrDefault(WorkState.EMPTY)

    private data class WorkState(
        val running: Boolean,
        val enqueued: Boolean,
        val runAttemptCount: Int,
        val stopReason: Int?,
        val progressInFlight: Int,
        val progress: IndexingWorkProgress,
    ) {
        companion object {
            val EMPTY = WorkState(false, false, 0, null, 0, IndexingWorkProgress(null, null, 0, 0))
        }

        val lastProgressAt: Long? get() = progress.lastProgressAt
        val nextAttemptAt: Long? get() = progress.nextAttemptAt
        val ratePerMinute: Double? get() = progress.ratePerMinute
        val etaMillis: Long? get() = progress.etaMillis
        val delayedRetryCount: Int get() = progress.delayedRetryCount
        val quarantinedCount: Int get() = progress.quarantinedCount
    }
}

internal object IndexingRuntimeStatePolicy {
    fun resolve(
        enabled: Boolean,
        pending: Int,
        failed: Int,
        admissionAllowed: Boolean,
        workRunning: Boolean,
        workEnqueued: Boolean,
        runAttemptCount: Int,
        foregroundActive: Boolean,
        pausedByUser: Boolean = false,
        unavailable: Boolean = false,
    ): IndexingPipelineState = when {
        !enabled -> IndexingPipelineState.STOPPED_BY_USER
        pausedByUser && pending > 0 -> IndexingPipelineState.PAUSED_BY_USER
        unavailable -> IndexingPipelineState.UNAVAILABLE
        foregroundActive && pending > 0 -> IndexingPipelineState.RUNNING
        workRunning -> IndexingPipelineState.RUNNING
        pending == 0 && failed > 0 -> IndexingPipelineState.DEGRADED
        pending == 0 -> IndexingPipelineState.COMPLETE
        !admissionAllowed -> IndexingPipelineState.WAITING_CONSTRAINTS
        workEnqueued && runAttemptCount > 0 -> IndexingPipelineState.BACKOFF
        workEnqueued -> IndexingPipelineState.WAITING_CONSTRAINTS
        else -> IndexingPipelineState.FAILED
    }
}

internal object IndexingSupervisor {
    fun reconcile(
        context: Context,
        summary: IndexSummary,
        people: PeopleIndexStatus,
        semantic: SemanticMemoryProgress,
        controls: IndexingJobControls,
        retrievalAvailable: Boolean,
    ) {
        if (!IndexingSupervisorPolicy.shouldScheduleBackgroundWork(
                foregroundActive = ForegroundIndexRuntime.active,
                pausedByUser = controls.foregroundPaused,
            )
        ) return
        if (!ForegroundIndexRuntime.active) {
            if (controls.mediaAnalysisEnabled && summary.pending > 0) IndexScheduler.schedule(context)
            if (
                controls.embeddingsEnabled &&
                retrievalAvailable &&
                summary.siglipVectorsPending > 0
            ) {
                EmbeddingIndexScheduler.schedule(context)
            }
            val captionBackfillPending = (semantic.captionCount - semantic.captionChunkCount).coerceAtLeast(0)
            if (
                controls.captionEmbeddingsEnabled &&
                retrievalAvailable &&
                (
                    captionBackfillPending > 0 ||
                        semantic.pendingCaptionChunkCount > 0 ||
                        semantic.runningCaptionChunkCount > 0
                    )
            ) {
                CaptionEmbeddingScheduler.schedule(context)
            }
        }
        if (controls.peopleEnabled && people.enabled && people.pendingMediaCount > 0) {
            PeopleIndexScheduler.schedule(context)
        }
        if (controls.semanticMemoryEnabled && semantic.hasActiveWork) {
            SemanticEnrichmentScheduler.schedule(
                context,
                userRequested = semantic.userRequestedPendingJobs > 0,
            )
        }
        SemanticPredicateScanScheduler.reconcile(context)
    }
}

internal object IndexingProgressWording {
    fun remainingBreakdown(mediaAnalysisPending: Int, peoplePending: Int): String = buildList {
        if (mediaAnalysisPending > 0) add("$mediaAnalysisPending media analysis")
        if (peoplePending > 0) add("$peoplePending face indexing")
    }.joinToString(" | ").ifBlank { "No pending items" }
}
