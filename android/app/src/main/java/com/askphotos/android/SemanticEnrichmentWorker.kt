package com.askphotos.android

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
        val application = applicationContext as AskPhotosApplication
        val services = application.services
        val admission = BackgroundWorkAdmissionPolicy(applicationContext).evaluate()
        if (!admission.allowed) return Result.retry()
        if (!services.modelPackManager.status().let { it.installed && it.multimodal }) return Result.success()
        val database = services.galleryDatabase
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
            val item = database.allItems().singleOrNull { it.id == currentJob.representativeMediaId }
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
                if (isStopped || !BackgroundWorkAdmissionPolicy(applicationContext).evaluate().allowed) {
                    database.failSemanticEnrichment(currentJob, "Background admission changed", retryable = true)
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
                database.failSemanticEnrichment(
                    currentJob,
                    error.message ?: error::class.java.simpleName,
                    retryable = true,
                )
                return Result.retry()
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
        const val MAX_JOBS_PER_RUN = 4
    }
}

object SemanticEnrichmentScheduler {
    private const val UNIQUE_WORK = "semantic-enrichment"

    fun schedule(context: Context, userRequested: Boolean = false) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK,
            if (userRequested) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request(userRequested),
        )
    }

    fun scheduleContinuation(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request(userRequested = true),
        )
    }

    private fun request(userRequested: Boolean) = OneTimeWorkRequestBuilder<SemanticEnrichmentWorker>()
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
        .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
        .addTag(UNIQUE_WORK)
        .build()
}
