package io.github.anup42.askalbum

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** Opt-in face compiler. It falls back to boxes until the verified Apache-2.0 SFace pack is installed. */
class PeopleIndexWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val repository = (appContext as AskAlbumApplication).repository
    private val imageLoader = GalleryImageLoader(appContext)
    private val workAdmission = BackgroundWorkAdmissionPolicy(appContext)
    private val services = (appContext as AskAlbumApplication).services
    private val jobControls = IndexingJobControlsStore(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!repository.peopleIndexStatus().enabled) return@withContext Result.success()
        if (!jobControls.load().peopleEnabled) return@withContext Result.success()
        if (!workAdmission.evaluate().allowed) return@withContext Result.retry()
        val faceLease = services.faceEngines.acquireOrNull()
        val detector = if (faceLease == null) MlKitFaceDetectionEngine() else null
        val embedder = faceLease?.engine
        val faceProducerVersion = faceLease?.descriptor?.producerVersion ?: MlKitFaceDetectionEngine.PRODUCER_VERSION
        val ownerId = id.toString()
        var retryableFailures = 0
        var quarantinedFailures = 0
        try {
            repository.recoverInterruptedJobs(IndexingPipeline.PEOPLE)
            if (embedder != null) services.faceVectorStore.reconcile(repository.allEmbeddedFaceIds())
            val pendingItems = repository.facePendingItems(BATCH_SIZE)
            var processed = 0
            publishProgress(processed, pendingItems.size, pendingItems.size, retryableFailures, quarantinedFailures)
            for ((index, item) in pendingItems.withIndex()) {
                if (isStopped || !repository.peopleIndexStatus().enabled || !jobControls.load().peopleEnabled) {
                    return@withContext Result.success()
                }
                if (!workAdmission.evaluate().allowed) return@withContext Result.retry()
                if (!repository.markFaces(item.id, faceProducerVersion, ownerId)) {
                    processed = index + 1
                    publishProgress(processed, pendingItems.size, pendingItems.size - processed, retryableFailures, quarantinedFailures)
                    continue
                }
                runCatching {
                    val jpeg = imageLoader.loadJpeg(item)
                    if (embedder == null) {
                        repository.completeFaces(item.id, requireNotNull(detector).detect(jpeg), faceProducerVersion, ownerId)
                    } else {
                        val oldFaceIds = repository.faceIdsForMedia(item.id)
                        // Only match against faces compiled before this media item. Two different people
                        // appearing together must not seed each other's automatic identity cluster.
                        val priorFaceIds = services.faceVectorStore.ids() - oldFaceIds.toSet()
                        val faces = requireNotNull(embedder).detectAndEmbed(jpeg.toModelImage())
                        val clusters = faces.mapIndexed { index, face ->
                            val faceId = "${item.id}:$index"
                            val neighbors = services.faceVectorStore.nearestNeighbors(
                                vector = face.embedding,
                                limit = CLUSTER_NEIGHBOR_COUNT,
                                allowedIds = priorFaceIds,
                            )
                            val references = repository.faceClusterReferences(neighbors.map(VectorHit::mediaId))
                            val matchedCluster = FaceClusterPolicy.matchingCluster(
                                neighbors.map { hit -> FaceClusterCandidate(hit, references[hit.mediaId]) },
                            )
                            val clusterId = matchedCluster ?: "person_${UUID.nameUUIDFromBytes(faceId.toByteArray()).toString().replace("-", "")}"
                            repository.ensureAutomaticPersonCluster(clusterId)
                            clusterId
                        }
                        repository.completeEmbeddedFaces(item.id, faces, clusters, faceProducerVersion, ownerId)
                        oldFaceIds.forEach { services.faceVectorStore.delete(it) }
                        faces.forEachIndexed { index, face -> services.faceVectorStore.upsert("${item.id}:$index", face.embedding) }
                    }
                }.onFailure { error ->
                    if (repository.peopleIndexStatus().enabled) {
                        val permanent = error is SecurityException || error is java.io.FileNotFoundException
                        repository.failFaces(item.id, error::class.java.simpleName, permanent, faceProducerVersion, ownerId)
                        if (permanent) {
                            quarantinedFailures++
                        } else {
                            retryableFailures++
                        }
                    }
                }
                processed = index + 1
                publishProgress(processed, pendingItems.size, pendingItems.size - processed, retryableFailures, quarantinedFailures)
            }
            val enabled = repository.peopleIndexStatus().enabled && jobControls.load().peopleEnabled
            val hasMore = repository.facePendingItems(1).isNotEmpty()
            val admission = workAdmission.evaluate()
            val shouldRetry = IndexingWorkerResultPolicy.shouldRetryWorker(
                processed = processed,
                retryableFailures = retryableFailures,
                stopped = isStopped,
                admissionAllowed = admission.allowed,
                hasImmediateWork = hasMore,
            )
            if (hasMore && enabled && !shouldRetry) {
                PeopleIndexScheduler.scheduleContinuation(applicationContext)
            } else if (!hasMore && enabled) {
                repository.nextPeopleRetryAt()?.let { retryAt ->
                    PeopleIndexScheduler.scheduleContinuation(
                        applicationContext,
                        (retryAt - System.currentTimeMillis()).coerceAtLeast(10_000L),
                    )
                }
            }
            when {
                !enabled -> Result.success()
                shouldRetry -> Result.retry()
                else -> Result.success()
            }
        } finally {
            detector?.close()
            faceLease?.close()
        }
    }

    private suspend fun publishProgress(
        processed: Int,
        eligible: Int,
        inFlight: Int,
        delayedRetries: Int,
        quarantined: Int,
    ) {
        setProgress(
            workDataOf(
                "processed" to processed,
                "eligible" to eligible,
                "in_flight" to inFlight.coerceAtLeast(0),
                "last_progress_at" to System.currentTimeMillis(),
                "next_attempt_at" to 0L,
                "delayed_retries" to delayedRetries,
                "quarantined" to quarantined,
            ),
        )
    }

    private companion object {
        const val BATCH_SIZE = 24
        const val CLUSTER_NEIGHBOR_COUNT = 12
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
