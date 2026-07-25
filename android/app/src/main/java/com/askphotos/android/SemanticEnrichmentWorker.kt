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
        if (database.hasAuthenticationProtectedOcr(job.representativeMediaId)) {
            database.failSemanticEnrichment(job, "Authentication required before semantic analysis", false, true)
            return Result.success()
        }
        val item = database.allItems().singleOrNull { it.id == job.representativeMediaId }
        if (item == null) {
            database.failSemanticEnrichment(job, "Representative media is unavailable", retryable = false)
            return Result.success()
        }
        return try {
            if (isStopped || !BackgroundWorkAdmissionPolicy(applicationContext).evaluate().allowed) {
                database.failSemanticEnrichment(job, "Background admission changed", retryable = true)
                Result.retry()
            } else {
                val hit = SearchHit(item, 0.0, emptyList())
                val loaded = GalleryImageLoader(applicationContext).loadForVerification(
                    hit,
                    database.videoKeyframes(item.id),
                )
                val facts = AdaptiveGemmaSemanticEnricher(
                    services.modelPackManager,
                    services.gemmaSessions,
                ).enrich(job, loaded.bytes)
                database.completeSemanticEnrichment(job, facts)
                if (database.hasPendingSemanticEnrichmentJobs()) Result.retry() else Result.success()
            }
        } catch (cancelled: CancellationException) {
            database.failSemanticEnrichment(job, "Enrichment cancelled", retryable = true)
            throw cancelled
        } catch (error: Throwable) {
            database.failSemanticEnrichment(job, error.message ?: error::class.java.simpleName, retryable = true)
            Result.retry()
        }
    }
}

object SemanticEnrichmentScheduler {
    private const val UNIQUE_WORK = "semantic-enrichment"

    fun schedule(context: Context, userRequested: Boolean = false) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .apply {
                if (!userRequested) {
                    setRequiresCharging(true)
                    setRequiresDeviceIdle(true)
                }
            }
            .build()
        val request = OneTimeWorkRequestBuilder<SemanticEnrichmentWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .addTag(UNIQUE_WORK)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK,
            if (userRequested) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
