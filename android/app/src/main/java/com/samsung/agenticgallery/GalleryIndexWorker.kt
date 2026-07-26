package com.samsung.agenticgallery

import android.content.Context
import android.util.Log
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
    private val jobControls = IndexingJobControlsStore(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!jobControls.load().mediaAnalysisEnabled) return@withContext Result.success()
        if (!workAdmission.evaluate().allowed) return@withContext Result.retry()
        repository.recoverInterruptedJobs()
        val budget = IndexingWorkerRunBudget()
        var batches = 0
        var processed = 0
        var retryableFailures = 0
        var permanentFailures = 0
        var hasMore = true
        var stoppedDuringBatch = false
        GalleryIndexBatchProcessor(applicationContext, repository).use { processor ->
            while (
                hasMore &&
                budget.hasTimeRemaining() &&
                !isStopped &&
                jobControls.load().mediaAnalysisEnabled &&
                workAdmission.evaluate().allowed
            ) {
                val batch = processor.processBatch(
                    canContinue = {
                        !isStopped &&
                            jobControls.load().mediaAnalysisEnabled &&
                            workAdmission.evaluate().allowed
                    },
                )
                batches++
                processed += batch.processed
                retryableFailures += batch.retryableFailures
                permanentFailures += batch.permanentFailures
                hasMore = batch.hasMore
                stoppedDuringBatch = batch.stopped
                if (batch.stopped || batch.retryableFailures > 0) break
            }
        }

        if (repository.peopleIndexStatus().enabled) PeopleIndexScheduler.schedule(applicationContext)
        val enabled = jobControls.load().mediaAnalysisEnabled
        val admission = workAdmission.evaluate()
        val shouldRetry = isStopped || stoppedDuringBatch || !admission.allowed || retryableFailures > 0
        if (hasMore && enabled && !shouldRetry) {
            IndexScheduler.scheduleContinuation(applicationContext)
        }
        Log.i(
            TAG,
            "Media analysis run finished batches=$batches processed=$processed hasMore=$hasMore " +
                "stopped=${isStopped || stoppedDuringBatch} retryableFailures=$retryableFailures " +
                "permanentFailures=$permanentFailures elapsedMs=${budget.elapsedMillis()}",
        )
        when {
            !enabled -> Result.success()
            shouldRetry -> Result.retry()
            else -> Result.success()
        }
    }

    private companion object {
        const val TAG = "AgenticGalleryIndex"
    }
}
