package com.samsung.agenticgallery

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
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request(context))
    }

    fun scheduleContinuation(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request(context))
    }

    fun restart(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelAllWorkByTag(UNIQUE_WORK).result.get(30, TimeUnit.SECONDS)
        workManager.enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request(context))
    }

    fun cancelAndWait(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(UNIQUE_WORK).result.get(30, TimeUnit.SECONDS)
    }

    private fun request(context: Context) = OneTimeWorkRequestBuilder<PeopleIndexWorker>()
        .setConstraints(indexingWorkerConstraints(context))
        .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
        .addTag(UNIQUE_WORK)
        .build()
}
