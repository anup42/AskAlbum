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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class EmbeddingIndexWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val app = appContext as AgenticGalleryApplication
    private val repository = app.repository
    private val workAdmission = BackgroundWorkAdmissionPolicy(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!workAdmission.evaluate().allowed) return@withContext Result.retry()
        repository.recoverInterruptedJobs()
        val batch = EmbeddingIndexBatchProcessor(
            context = applicationContext,
            repository = repository,
            vectors = app.services.semanticVectorStore,
            engine = app.services.embeddingEngine,
        ).processBatch(canContinue = { !isStopped && workAdmission.evaluate().allowed })
        if (batch.hasMore) EmbeddingIndexScheduler.scheduleContinuation(applicationContext)
        if (batch.stopped || batch.retryableFailures > 0) Result.retry() else Result.success()
    }
}

internal object EmbeddingBatchPolicy {
    fun forDevice(memoryClassMb: Int, totalRamMb: Int): Int = when {
        memoryClassMb <= 192 || totalRamMb < 4_096 -> 4
        totalRamMb < 6_144 -> 12
        else -> 24
    }
}

object EmbeddingIndexScheduler {
    private const val UNIQUE_WORK = "gallery-image-embeddings"

    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request(context))
    }

    fun scheduleContinuation(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request(context),
        )
    }

    fun restart(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelAllWorkByTag(UNIQUE_WORK).result.get(30, TimeUnit.SECONDS)
        workManager.enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request(context))
    }

    fun cancelAndWait(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(UNIQUE_WORK).result.get(30, TimeUnit.SECONDS)
    }

    private fun request(context: Context) = OneTimeWorkRequestBuilder<EmbeddingIndexWorker>()
        .setConstraints(indexingWorkerConstraints(context))
        .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
        .addTag(UNIQUE_WORK)
        .build()
}
