package com.askphotos.android

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** Opt-in face compiler. It falls back to boxes until the verified Apache-2.0 SFace pack is installed. */
class PeopleIndexWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val repository = (appContext as AskPhotosApplication).repository
    private val imageLoader = GalleryImageLoader(appContext)
    private val workAdmission = BackgroundWorkAdmissionPolicy(appContext)
    private val services = (appContext as AskPhotosApplication).services

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!repository.peopleIndexStatus().enabled) return@withContext Result.success()
        if (!workAdmission.evaluate().allowed) return@withContext Result.retry()
        val installed = services.faceModelPackManager.current()
        val detector = if (installed == null) MlKitFaceDetectionEngine() else null
        val embedder = if (installed != null) OpenCvSFaceEngine(services.faceModelPackManager) else null
        var retryableFailure = false
        try {
            if (installed != null) services.faceVectorStore.reconcile(repository.allEmbeddedFaceIds())
            repository.facePendingItems(BATCH_SIZE).forEach { item ->
                if (isStopped || !repository.peopleIndexStatus().enabled) return@withContext Result.success()
                if (!workAdmission.evaluate().allowed) return@withContext Result.retry()
                repository.markFaces(item.id)
                runCatching {
                    val jpeg = imageLoader.loadJpeg(item)
                    if (installed == null) {
                        repository.completeFaces(item.id, requireNotNull(detector).detect(jpeg), MlKitFaceDetectionEngine.PRODUCER_VERSION)
                    } else {
                        val oldFaceIds = repository.faceIdsForMedia(item.id)
                        // Only match against faces compiled before this media item. Two different people
                        // appearing together must not seed each other's automatic identity cluster.
                        val priorFaceIds = services.faceVectorStore.ids() - oldFaceIds.toSet()
                        val faces = requireNotNull(embedder).detectAndEmbed(jpeg.toModelImage())
                        val clusters = faces.mapIndexed { index, face ->
                            val faceId = "${item.id}:$index"
                            val nearest = services.faceVectorStore.nearest(face.embedding, priorFaceIds)
                            val matchedCluster = nearest?.let { hit ->
                                FaceClusterPolicy.matchingCluster(hit, repository.clusterIdForFace(hit.mediaId))
                            }
                            val clusterId = matchedCluster ?: "person_${UUID.nameUUIDFromBytes(faceId.toByteArray()).toString().replace("-", "")}"
                            repository.ensureAutomaticPersonCluster(clusterId)
                            clusterId
                        }
                        repository.completeEmbeddedFaces(item.id, faces, clusters, installed.spec.producerVersion)
                        oldFaceIds.forEach { services.faceVectorStore.delete(it) }
                        faces.forEachIndexed { index, face -> services.faceVectorStore.upsert("${item.id}:$index", face.embedding) }
                    }
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
            detector?.close()
            embedder?.close()
        }
    }

    private companion object {
        const val BATCH_SIZE = 24
    }

    private fun ByteArray.toModelImage(): ModelImage {
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(this, 0, size)) { "Face image could not be decoded" }
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val rgb = ByteArray(pixels.size * 3)
            pixels.forEachIndexed { index, pixel ->
                rgb[index * 3] = Color.red(pixel).toByte()
                rgb[index * 3 + 1] = Color.green(pixel).toByte()
                rgb[index * 3 + 2] = Color.blue(pixel).toByte()
            }
            ModelImage(rgb, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }
}
