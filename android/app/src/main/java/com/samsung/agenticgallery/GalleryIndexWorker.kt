package com.samsung.agenticgallery

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GalleryIndexWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val repository = (appContext as AgenticGalleryApplication).repository
    private val workAdmission = BackgroundWorkAdmissionPolicy(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!workAdmission.evaluate().allowed) return@withContext Result.retry()
        repository.recoverInterruptedJobs()
        val batch = GalleryIndexBatchProcessor(applicationContext, repository).use { processor ->
            processor.processBatch(canContinue = { !isStopped && workAdmission.evaluate().allowed })
        }
        if (repository.peopleIndexStatus().enabled) PeopleIndexScheduler.schedule(applicationContext)
        if (batch.hasMore) IndexScheduler.scheduleContinuation(applicationContext)
        if (batch.stopped || batch.retryableFailures > 0) Result.retry() else Result.success()
    }
}
