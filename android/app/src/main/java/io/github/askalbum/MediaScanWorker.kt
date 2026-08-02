package io.github.anup42.askalbum

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaScanWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching { (applicationContext as AskAlbumApplication).repository.scanAccessibleGallery() }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }
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
