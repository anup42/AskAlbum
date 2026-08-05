package io.github.anup42.askalbum

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class SemanticEnrichmentWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        Log.i(TAG, "Semantic enrichment worker started")
        val application = applicationContext as AskAlbumApplication
        val services = application.services
        val jobControls = IndexingJobControlsStore(applicationContext)
        if (!jobControls.load().semanticMemoryEnabled) {
            Log.i(TAG, "Semantic enrichment worker stopped by user control")
            return Result.success()
        }
        if (ForegroundIndexLanePolicy.shouldDeferBackgroundWorker(ForegroundIndexRuntime.active)) {
            Log.i(TAG, "Semantic enrichment deferred while foreground indexing is active")
            return Result.retry()
        }
        val admission = BackgroundWorkAdmissionPolicy(applicationContext).evaluate()
        if (!admission.allowed) {
            Log.i(TAG, "Semantic enrichment deferred: ${admission.reason}")
            return Result.retry()
        }
        val database = services.galleryDatabase
        val modelStatus = services.modelPackManager.status()
        if (SemanticEnrichmentAvailabilityPolicy.shouldRetryForUnavailableModel(
                modelInstalled = modelStatus.installed,
                modelMultimodal = modelStatus.multimodal,
                hasPendingJobs = database.hasPendingSemanticEnrichmentJobs(),
            )
        ) {
            Log.w(TAG, "Semantic enrichment unavailable: no verified multimodal Gemma generation")
            setProgress(
                workDataOf(
                    "status" to "UNAVAILABLE",
                    "error_code" to "VERIFIED_MULTIMODAL_MODEL_REQUIRED",
                    "last_progress_at" to System.currentTimeMillis(),
                    "next_attempt_at" to 0L,
                ),
            )
            return Result.retry()
        }
        if (database.semanticEnrichmentPlanNeedsRebuild()) {
            SemanticEnrichmentCoordinator(database).rebuildPlan(
                userRequested = true,
                modelVersion = modelStatus.packVersion,
            )
        }
        var job = database.claimSemanticEnrichmentJob(owner = id.toString())
        if (job == null) {
            SemanticEnrichmentCoordinator(database).rebuildPlan(
                modelVersion = modelStatus.packVersion,
            )
            job = database.claimSemanticEnrichmentJob(owner = id.toString())
            if (job == null) {
                if (database.hasPendingSemanticEnrichmentJobs()) {
                    schedulePendingContinuation(database)
                } else {
                    Log.i(TAG, "Semantic enrichment queue is complete")
                }
                return Result.success()
            }
        }
        var processed = 0
        var retryableFailures = 0
        var quarantinedFailures = 0
        val progressStartedAt = System.currentTimeMillis()
        suspend fun publishProgress(inFlight: Int) {
            val semanticProgress = database.semanticMemoryProgress(modelStatus.packVersion)
            val estimate = IndexingProgressEstimate.calculate(
                processed = processed,
                remaining = semanticProgress.pendingJobs + semanticProgress.runningJobs,
                startedAtMillis = progressStartedAt,
            )
            setProgress(
                workDataOf(
                    "processed" to processed,
                    "failed" to retryableFailures + quarantinedFailures,
                    "in_flight" to inFlight.coerceAtLeast(0),
                    "last_progress_at" to System.currentTimeMillis(),
                    "next_attempt_at" to (database.nextSemanticEnrichmentRetryAt() ?: 0L),
                    "delayed_retries" to retryableFailures,
                    "quarantined" to quarantinedFailures,
                    "rate_per_minute" to (estimate.ratePerMinute ?: 0.0),
                    "eta_millis" to (estimate.etaMillis ?: 0L),
                ),
            )
        }
        publishProgress(1)
        while (job != null && processed < MAX_JOBS_PER_RUN) {
            val currentJob = job
            if (database.hasAuthenticationProtectedOcr(currentJob.representativeMediaId)) {
                database.failSemanticEnrichment(
                    currentJob,
                    "Authentication required before semantic analysis",
                    false,
                    true,
                )
                processed += 1
                job = database.claimSemanticEnrichmentJob(owner = id.toString())
                publishProgress(if (job != null) 1 else 0)
                continue
            }
            val item = database.itemById(currentJob.representativeMediaId)
            if (item == null) {
                database.failSemanticEnrichment(
                    currentJob,
                    "Representative media is unavailable",
                    retryable = false,
                )
                processed += 1
                job = database.claimSemanticEnrichmentJob(owner = id.toString())
                publishProgress(if (job != null) 1 else 0)
                continue
            }
            try {
                val currentAdmission = BackgroundWorkAdmissionPolicy(applicationContext).evaluate()
                if (isStopped || !jobControls.load().semanticMemoryEnabled || !currentAdmission.allowed) {
                    database.failSemanticEnrichment(
                        currentJob,
                        currentAdmission.reason ?: "Semantic memory indexing stopped",
                        retryable = true,
                    )
                    return if (jobControls.load().semanticMemoryEnabled) Result.retry() else Result.success()
                }
                val hit = SearchHit(item, 0.0, emptyList())
                val loaded = GalleryImageLoader(applicationContext).loadForVerification(
                    hit,
                    database.videoKeyframes(item.id),
                )
                val bindings = database.reviewedFaceBindingsForMedia(item.id)
                if (PersonalSemanticMemoryPolicy.isPersonalJob(currentJob.reason)) {
                    val digest = PersonalSemanticMemoryPolicy.exactContentDigest(loaded.bytes, item.width, item.height)
                    database.recordExactContentDigest(item.id, digest)
                    if (database.reuseExactDuplicateSemanticEnrichment(currentJob, bindings, digest)) {
                        CaptionEmbeddingScheduler.schedule(applicationContext)
                        Log.i(TAG, "Reused exact-duplicate personal caption for media=${item.id}")
                        processed += 1
                        job = database.claimSemanticEnrichmentJob(owner = id.toString())
                        publishProgress(if (job != null) 1 else 0)
                        continue
                    }
                }
                val modelImage = PersonVerificationImageComposer.compose(loaded.bytes, bindings)
                val enricher = AdaptiveGemmaSemanticEnricher(
                    services.modelPackManager,
                    services.gemmaSessions,
                )
                val result = IndexingResourceCoordinator.withBackgroundPermit {
                    try {
                        enricher.enrich(currentJob, modelImage, bindings)
                    } catch (malformed: SemanticEnrichmentOutputException) {
                        Log.w(TAG, "Retrying malformed Gemma semantic output once for job=${currentJob.id}")
                        enricher.enrich(
                            currentJob,
                            modelImage,
                            bindings,
                            repairReason = malformed.message ?: "invalid structured output",
                        )
                    }
                }
                database.completeSemanticEnrichment(currentJob, result)
                CaptionEmbeddingScheduler.schedule(applicationContext)
                Log.i(
                    TAG,
                    "Semantic enrichment completed job=${currentJob.id} media=${currentJob.representativeMediaId} " +
                        "facts=${result.facts.size} caption=${result.caption != null} personFacts=${result.personFacts.size}",
                )
            } catch (cancelled: CancellationException) {
                database.failSemanticEnrichment(currentJob, "Enrichment cancelled", retryable = true)
                Log.i(TAG, "Semantic enrichment cancelled job=${currentJob.id}")
                throw cancelled
            } catch (error: Throwable) {
                val retryable = SemanticEnrichmentFailurePolicy.isRetryable(error)
                val failureMessage = if (error is SemanticEnrichmentOutputException) {
                    "Repair exhausted: ${error.message ?: "invalid structured output"}"
                } else {
                    error.message ?: error::class.java.simpleName
                }
                database.failSemanticEnrichment(
                    currentJob,
                    failureMessage,
                    retryable = retryable,
                )
                Log.e(TAG, "Semantic enrichment failed job=${currentJob.id} retryable=$retryable", error)
                if (retryable) retryableFailures += 1
                if (!retryable) quarantinedFailures += 1
            }
            processed += 1
            job = database.claimSemanticEnrichmentJob(owner = id.toString())
            publishProgress(if (job != null) 1 else 0)
        }
        val hasPending = database.hasPendingSemanticEnrichmentJobs()
        if (hasPending) {
            Log.i(TAG, "Semantic enrichment scheduling continuation after $processed representatives")
            schedulePendingContinuation(database)
        } else {
            Log.i(TAG, "Semantic enrichment queue completed")
        }
        val shouldRetry = IndexingWorkerResultPolicy.shouldRetryWorker(
            processed = processed,
            retryableFailures = retryableFailures,
            stopped = false,
            admissionAllowed = true,
            hasImmediateWork = hasPending,
        )
        return if (shouldRetry) Result.retry() else Result.success()
    }

    private fun schedulePendingContinuation(database: GalleryDatabase) {
        val nextAttemptAt = database.nextSemanticEnrichmentRetryAt()
        val delayMillis = if (nextAttemptAt == null) {
            CONTINUATION_COOLING_DELAY_MILLIS
        } else {
            (nextAttemptAt - System.currentTimeMillis())
                .coerceAtLeast(CONTINUATION_COOLING_DELAY_MILLIS)
        }
        SemanticEnrichmentScheduler.scheduleContinuation(applicationContext, delayMillis)
    }

    private companion object {
        const val TAG = "AskAlbumSemantic"
        const val MAX_JOBS_PER_RUN = 4
        const val CONTINUATION_COOLING_DELAY_MILLIS = 5_000L
    }
}

internal object SemanticEnrichmentFailurePolicy {
    fun isRetryable(error: Throwable): Boolean = error !is SemanticEnrichmentOutputException
}

object SemanticEnrichmentScheduler {
    private const val UNIQUE_WORK = "semantic-enrichment"
    private const val USER_REQUESTED_START_DELAY_SECONDS = 1L
    private const val CONTINUATION_COOLING_DELAY_SECONDS = 5L

    fun schedule(context: Context, userRequested: Boolean = false) {
        val controls = IndexingJobControlsStore(context).load()
        if (!controls.semanticMemoryEnabled || controls.foregroundPaused) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.KEEP,
            request(context, userRequested, USER_REQUESTED_START_DELAY_SECONDS),
        )
    }

    fun scheduleContinuation(
        context: Context,
        initialDelayMillis: Long = CONTINUATION_COOLING_DELAY_SECONDS * 1_000L,
    ) {
        val controls = IndexingJobControlsStore(context).load()
        if (!controls.semanticMemoryEnabled || controls.foregroundPaused) return
        val initialDelaySeconds = ((initialDelayMillis + 999L) / 1_000L).coerceAtLeast(1L)
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request(context, userRequested = true, initialDelaySeconds = initialDelaySeconds),
        )
    }

    fun restart(context: Context, userRequested: Boolean = false) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelAllWorkByTag(UNIQUE_WORK).result.get(30, TimeUnit.SECONDS)
        val controls = IndexingJobControlsStore(context).load()
        if (controls.semanticMemoryEnabled && !controls.foregroundPaused) {
            workManager.enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.REPLACE,
                request(context, userRequested, USER_REQUESTED_START_DELAY_SECONDS),
            )
        }
    }

    fun cancelAndWait(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(UNIQUE_WORK).result.get(30, TimeUnit.SECONDS)
    }

    fun hasActiveWork(context: Context): Boolean = hasActiveIndexingWork(context, UNIQUE_WORK)

    private fun request(
        context: Context,
        userRequested: Boolean,
        initialDelaySeconds: Long = 0L,
    ) = OneTimeWorkRequestBuilder<SemanticEnrichmentWorker>()
        .setConstraints(
            indexingWorkerConstraints(
                context = context,
                forceCharging = !userRequested,
                requireDeviceIdle = !userRequested,
            ),
        )
        .apply {
            if (initialDelaySeconds > 0L) setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
            if (userRequested) setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
        }
        .addTag(UNIQUE_WORK)
        .build()
}
