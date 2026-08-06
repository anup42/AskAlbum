package io.github.anup42.askalbum

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaScanWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            (applicationContext as AskAlbumApplication).repository.scanAccessibleGallery()
            Result.success()
        } catch (error: Throwable) {
            if (MediaScanFailurePolicy.shouldPropagate(error)) throw error
            Result.retry()
        }
    }
}

internal object MediaScanFailurePolicy {
    fun shouldPropagate(error: Throwable): Boolean = error is CancellationException
}

object MediaScanScheduler {
    private const val UNIQUE_WORK = "gallery-media-scan"

    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MediaScanWorker>().addTag(UNIQUE_WORK).build(),
        )
    }
}
