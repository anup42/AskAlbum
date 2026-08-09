package io.github.anup42.askalbum

import android.content.Context
import androidx.work.Constraints
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object PeopleIndexScheduler {
    private const val UNIQUE_WORK = "gallery-people-index"

    fun schedule(context: Context) {
        val controls = IndexingJobControlsStore(context).load()
        if (!controls.peopleEnabled || controls.foregroundPaused) return
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request(context))
    }

    fun scheduleContinuation(context: Context, initialDelayMillis: Long = 0L) {
        val controls = IndexingJobControlsStore(context).load()
        if (!controls.peopleEnabled || controls.foregroundPaused) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request(context, initialDelayMillis),
        )
    }

    fun restart(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelAllWorkByTag(UNIQUE_WORK).result.get(30, TimeUnit.SECONDS)
        val controls = IndexingJobControlsStore(context).load()
        if (controls.peopleEnabled && !controls.foregroundPaused) {
            workManager.enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request(context))
        }
    }

    fun cancelAndWait(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(UNIQUE_WORK).result.get(30, TimeUnit.SECONDS)
    }

    fun hasActiveWork(context: Context): Boolean = hasActiveIndexingWork(context, UNIQUE_WORK)

    private fun request(context: Context, initialDelayMillis: Long = 0L) = OneTimeWorkRequestBuilder<PeopleIndexWorker>()
        .setConstraints(indexingWorkerConstraints(context))
        .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
        .setInitialDelay(initialDelayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        .addTag(UNIQUE_WORK)
        .build()
}
