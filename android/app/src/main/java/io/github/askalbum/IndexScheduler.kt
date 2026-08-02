package io.github.anup42.askalbum

import android.content.Context
import androidx.work.Constraints
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object IndexScheduler {
    private const val UNIQUE_WORK = "gallery-index"

    fun schedule(context: Context) {
        if (!IndexingJobControlsStore(context).load().mediaAnalysisEnabled) return
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request(context))
    }

    fun scheduleContinuation(context: Context, initialDelayMillis: Long = 0L) {
        if (!IndexingJobControlsStore(context).load().mediaAnalysisEnabled) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request(context, initialDelayMillis),
        )
    }

    fun restart(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelAllWorkByTag(UNIQUE_WORK).result.get(30, TimeUnit.SECONDS)
        if (IndexingJobControlsStore(context).load().mediaAnalysisEnabled) {
            workManager.enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request(context))
        }
    }

    fun cancelAndWait(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(UNIQUE_WORK).result.get(30, TimeUnit.SECONDS)
    }

    private fun request(context: Context, initialDelayMillis: Long = 0L) =
        OneTimeWorkRequestBuilder<GalleryIndexWorker>()
            .setConstraints(indexingWorkerConstraints(context))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .addTag(UNIQUE_WORK)
            .apply {
                if (initialDelayMillis > 0L) setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            }
            .build()
}
