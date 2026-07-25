package com.samsung.agenticgallery

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class SemanticEnrichmentWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val application = applicationContext as AgenticGalleryApplication
        val services = application.services
        val admission = BackgroundWorkAdmissionPolicy(applicationContext).evaluate()
        if (!admission.allowed) return Result.retry()
        if (!services.modelPackManager.status().let { it.installed && it.multimodal }) return Result.success()
        val database = services.galleryDatabase
        if (database.semanticEnrichmentPlanNeedsRebuild()) {
            SemanticEnrichmentCoordinator(database).rebuildPlan(userRequested = true)
        }
        var job = database.claimSemanticEnrichmentJob()
        if (job == null) {
            SemanticEnrichmentCoordinator(database).rebuildPlan()
            job = database.claimSemanticEnrichmentJob() ?: return Result.success()
        }
        var processed = 0
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
                job = database.claimSemanticEnrichmentJob()
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
                job = database.claimSemanticEnrichmentJob()
                continue
            }
            try {
                val currentAdmission = BackgroundWorkAdmissionPolicy(applicationContext).evaluate()
                if (isStopped || !currentAdmission.allowed) {
                    database.failSemanticEnrichment(
                        currentJob,
                        currentAdmission.reason ?: "Background admission changed",
                        retryable = true,
                    )
                    return Result.retry()
                }
                val hit = SearchHit(item, 0.0, emptyList())
                val loaded = GalleryImageLoader(applicationContext).loadForVerification(
                    hit,
                    database.videoKeyframes(item.id),
                )
                val facts = AdaptiveGemmaSemanticEnricher(
                    services.modelPackManager,
                    services.gemmaSessions,
                ).enrich(currentJob, loaded.bytes)
                database.completeSemanticEnrichment(currentJob, facts)
            } catch (cancelled: CancellationException) {
                database.failSemanticEnrichment(currentJob, "Enrichment cancelled", retryable = true)
                throw cancelled
            } catch (error: Throwable) {
                val retryable = SemanticEnrichmentFailurePolicy.isRetryable(error)
                database.failSemanticEnrichment(
                    currentJob,
                    error.message ?: error::class.java.simpleName,
                    retryable = retryable,
                )
                if (retryable) return Result.retry()
            }
            processed += 1
            job = database.claimSemanticEnrichmentJob()
        }
        if (database.hasPendingSemanticEnrichmentJobs()) {
            SemanticEnrichmentScheduler.scheduleContinuation(applicationContext)
        }
        return Result.success()
    }

    private companion object {
        const val MAX_JOBS_PER_RUN = 2
    }
}

internal object SemanticEnrichmentFailurePolicy {
    fun isRetryable(error: Throwable): Boolean = error !is SemanticEnrichmentOutputException
}

object SemanticEnrichmentScheduler {
    private const val UNIQUE_WORK = "semantic-enrichment"
    private const val USER_REQUESTED_START_DELAY_SECONDS = 1L
    private const val CONTINUATION_COOLING_DELAY_SECONDS = 30L

    fun schedule(context: Context, userRequested: Boolean = false) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK,
            if (userRequested) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request(userRequested, USER_REQUESTED_START_DELAY_SECONDS),
        )
    }

    fun scheduleContinuation(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request(userRequested = true, initialDelaySeconds = CONTINUATION_COOLING_DELAY_SECONDS),
        )
    }

    private fun request(
        userRequested: Boolean,
        initialDelaySeconds: Long = 0L,
    ) = OneTimeWorkRequestBuilder<SemanticEnrichmentWorker>()
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .apply {
                    if (!userRequested) {
                        setRequiresCharging(true)
                        setRequiresDeviceIdle(true)
                    }
                }
                .build(),
        )
        .apply {
            if (initialDelaySeconds > 0L) setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
            if (userRequested) setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
        }
        .addTag(UNIQUE_WORK)
        .build()
}
