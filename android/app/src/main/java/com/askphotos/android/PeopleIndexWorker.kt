package com.askphotos.android

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Opt-in face-box compiler. Identity embeddings and clustering remain unavailable until a licensed pack is installed. */
class PeopleIndexWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val repository = (appContext as AskPhotosApplication).repository
    private val imageLoader = GalleryImageLoader(appContext)
    private val workAdmission = BackgroundWorkAdmissionPolicy(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!repository.peopleIndexStatus().enabled) return@withContext Result.success()
        if (!workAdmission.evaluate().allowed) return@withContext Result.retry()
        val detector = MlKitFaceDetectionEngine()
        var retryableFailure = false
        try {
            repository.facePendingItems(BATCH_SIZE).forEach { item ->
                if (isStopped || !repository.peopleIndexStatus().enabled) return@withContext Result.success()
                if (!workAdmission.evaluate().allowed) return@withContext Result.retry()
                repository.markFaces(item.id)
                runCatching {
                    val jpeg = imageLoader.loadJpeg(item)
                    repository.completeFaces(item.id, detector.detect(jpeg), MlKitFaceDetectionEngine.PRODUCER_VERSION)
                }.onFailure { error ->
                    if (repository.peopleIndexStatus().enabled) {
                        val permanent = error is SecurityException || error is java.io.FileNotFoundException
                        repository.failFaces(item.id, error::class.java.simpleName, permanent)
                        retryableFailure = retryableFailure || !permanent
                    }
                }
            }
            if (repository.peopleIndexStatus().enabled && repository.facePendingItems(1).isNotEmpty()) {
                PeopleIndexScheduler.scheduleContinuation(applicationContext)
            }
            if (retryableFailure) Result.retry() else Result.success()
        } finally {
            detector.close()
        }
    }

    private companion object {
        const val BATCH_SIZE = 24
    }
}
