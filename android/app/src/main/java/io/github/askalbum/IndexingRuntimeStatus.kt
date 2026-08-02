package io.github.anup42.askalbum

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

enum class IndexingPipelineState {
    RUNNING,
    WAITING_CONSTRAINTS,
    BACKOFF,
    STOPPED_BY_USER,
    COMPLETE,
    DEGRADED,
    FAILED,
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
    val stopReason: Int? = null,
    val message: String,
)

internal class IndexingRuntimeStatusReader(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun read(
        summary: IndexSummary,
        people: PeopleIndexStatus,
        semantic: SemanticMemoryProgress,
        controls: IndexingJobControls,
        admission: BackgroundWorkAdmission,
    ): Map<IndexingJob, IndexingPipelineSnapshot> {
        val media = workState("gallery-index")
        val embeddings = workState("gallery-image-embeddings")
        val peopleWork = workState("gallery-people-index")
        val semanticWork = workState("semantic-enrichment")
        val mediaCompleted = (summary.discovered - summary.pending - summary.failed).coerceAtLeast(0)
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
            ),
            IndexingJob.EMBEDDINGS to snapshot(
                IndexingJob.EMBEDDINGS,
                controls.embeddingsEnabled,
                (summary.discovered - summary.siglipVectorsReady).coerceAtLeast(0),
                0,
                summary.siglipVectorsReady,
                summary.discovered,
                embeddings,
                admission,
            ),
            IndexingJob.PEOPLE to snapshot(
                IndexingJob.PEOPLE,
                controls.peopleEnabled && people.enabled,
                people.pendingMediaCount,
                0,
                (summary.discovered - people.pendingMediaCount).coerceAtLeast(0),
                summary.discovered,
                peopleWork,
                admission,
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
    ): IndexingPipelineSnapshot {
        val state = when {
            !enabled -> IndexingPipelineState.STOPPED_BY_USER
            work.running -> IndexingPipelineState.RUNNING
            pending == 0 && failed > 0 -> IndexingPipelineState.DEGRADED
            pending == 0 -> IndexingPipelineState.COMPLETE
            !admission.allowed -> IndexingPipelineState.WAITING_CONSTRAINTS
            work.enqueued && work.runAttemptCount > 0 -> IndexingPipelineState.BACKOFF
            work.enqueued -> IndexingPipelineState.WAITING_CONSTRAINTS
            else -> IndexingPipelineState.FAILED
        }
        val message = when (state) {
            IndexingPipelineState.RUNNING -> "$completed / $eligible indexed"
            IndexingPipelineState.WAITING_CONSTRAINTS -> admission.reason ?: "Queued for the next available run"
            IndexingPipelineState.BACKOFF -> "Retry backoff after ${work.runAttemptCount} worker attempts"
            IndexingPipelineState.STOPPED_BY_USER -> "Stopped"
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
            delayedRetryCount = if (state == IndexingPipelineState.BACKOFF) pending else 0,
            quarantinedCount = failed,
            stopReason = work.stopReason,
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
        )
    }.getOrDefault(WorkState.EMPTY)

    private data class WorkState(
        val running: Boolean,
        val enqueued: Boolean,
        val runAttemptCount: Int,
        val stopReason: Int?,
        val progressInFlight: Int,
    ) {
        companion object {
            val EMPTY = WorkState(false, false, 0, null, 0)
        }
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
        if (!ForegroundIndexRuntime.active) {
            if (controls.mediaAnalysisEnabled && summary.pending > 0) IndexScheduler.schedule(context)
            if (
                controls.embeddingsEnabled &&
                retrievalAvailable &&
                summary.siglipVectorsReady < summary.discovered
            ) {
                EmbeddingIndexScheduler.schedule(context)
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
    }
}

internal object IndexingProgressWording {
    fun remainingBreakdown(mediaAnalysisPending: Int, peoplePending: Int): String = buildList {
        if (mediaAnalysisPending > 0) add("$mediaAnalysisPending media analysis")
        if (peoplePending > 0) add("$peoplePending face indexing")
    }.joinToString(" | ").ifBlank { "No pending items" }
}
