package io.github.anup42.askalbum

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CaptionEmbeddingWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val application = applicationContext as AskAlbumApplication
        val controls = IndexingJobControlsStore(applicationContext)
        if (!controls.load().captionEmbeddingsEnabled) return@withContext Result.success()
        val admission = BackgroundWorkAdmissionPolicy(applicationContext).evaluate()
        if (!admission.allowed) return@withContext Result.retry()

        val services = application.services
        val producer = services.captionVectorStore.producerVersion()
            ?: run {
                setProgress(
                    workDataOf(
                        "status" to "UNAVAILABLE",
                        "error_code" to "NO_VERIFIED_RETRIEVAL_PACK",
                        "last_progress_at" to System.currentTimeMillis(),
                    ),
                )
                return@withContext Result.retry()
            }
        val database = services.galleryDatabase
        database.recoverCaptionEmbeddingClaims()
        database.prepareCaptionEmbeddingVersion(producer)
        database.materializeCaptionChunkBackfill(BACKFILL_CAPTIONS_PER_RUN)

        var processed = 0
        var failures = 0
        val chunks = database.claimCaptionEmbeddingChunks(id.toString(), producer, CHUNKS_PER_RUN)
        if (chunks.isNotEmpty()) {
            try {
                val vectors = IndexingResourceCoordinator.withBackgroundPermit {
                    services.captionVectorStore.embedTexts(chunks.map(SemanticCaptionChunkRecord::exactText))
                }
                chunks.zip(vectors).forEach { (chunk, vector) ->
                    try {
                        services.captionVectorStore.upsert(chunk.id, vector)
                        database.completeCaptionEmbedding(chunk.id, producer, id.toString())
                        processed++
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        database.failCaptionEmbedding(
                            chunk.id,
                            producer,
                            id.toString(),
                            error.message ?: error::class.java.simpleName,
                            retryable = true,
                        )
                        failures++
                    }
                }
            } catch (cancelled: CancellationException) {
                database.releaseCaptionEmbeddingClaims(id.toString(), "worker_cancelled")
                throw cancelled
            } catch (error: Throwable) {
                chunks.forEach {
                    database.failCaptionEmbedding(
                        it.id,
                        producer,
                        id.toString(),
                        error.message ?: error::class.java.simpleName,
                        retryable = true,
                    )
                }
                failures += chunks.size
            }
        }

        setProgress(
            workDataOf(
                "processed" to processed,
                "failed" to failures,
                "in_flight" to 0,
                "last_progress_at" to System.currentTimeMillis(),
                "next_attempt_at" to 0L,
                "delayed_retries" to failures,
                "quarantined" to 0,
            ),
        )
        val hasMore = database.hasCaptionEmbeddingWork(producer)
        if (hasMore && controls.load().captionEmbeddingsEnabled) {
            CaptionEmbeddingScheduler.scheduleContinuation(applicationContext)
        } else if (!hasMore) {
            runCatching {
                services.captionVectorStore.reconcile(database.currentCaptionEmbeddingChunkIds(producer))
            }
        }
        Log.i(TAG, "Caption embedding run processed=$processed failures=$failures hasMore=$hasMore")
        if (processed == 0 && failures > 0) Result.retry() else Result.success()
    }

    private companion object {
        const val TAG = "AskAlbumCaptionVector"
        const val BACKFILL_CAPTIONS_PER_RUN = 8
        const val CHUNKS_PER_RUN = 24
    }
}

object CaptionEmbeddingScheduler {
    private const val UNIQUE_WORK = "gallery-caption-embeddings"

    fun schedule(context: Context) {
        if (!IndexingJobControlsStore(context).load().captionEmbeddingsEnabled) return
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request(context))
    }

    fun scheduleContinuation(context: Context, delayMillis: Long = 2_000L) {
        if (!IndexingJobControlsStore(context).load().captionEmbeddingsEnabled) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request(context, delayMillis),
        )
    }

    fun restart(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelAllWorkByTag(UNIQUE_WORK).result.get(30, TimeUnit.SECONDS)
        if (IndexingJobControlsStore(context).load().captionEmbeddingsEnabled) {
            manager.enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request(context))
        }
    }

    fun cancelAndWait(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(UNIQUE_WORK).result.get(30, TimeUnit.SECONDS)
    }

    fun hasActiveWork(context: Context): Boolean = hasActiveIndexingWork(context, UNIQUE_WORK)

    private fun request(context: Context, delayMillis: Long = 0L) =
        OneTimeWorkRequestBuilder<CaptionEmbeddingWorker>()
            .setConstraints(indexingWorkerConstraints(context))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .addTag(UNIQUE_WORK)
            .apply { if (delayMillis > 0L) setInitialDelay(delayMillis, TimeUnit.MILLISECONDS) }
            .build()
}
