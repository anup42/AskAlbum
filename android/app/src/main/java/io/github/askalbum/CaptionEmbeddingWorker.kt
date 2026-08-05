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
        if (ForegroundIndexLanePolicy.shouldDeferBackgroundWorker(ForegroundIndexRuntime.active)) {
            return@withContext Result.retry()
        }

        val services = application.services
        val database = services.galleryDatabase
        database.recoverCaptionEmbeddingClaims()
        val producer = services.captionVectorStore.producerVersion()
            ?: run {
                val hasPendingWork = database.hasCaptionEmbeddingBackfillWork()
                setProgress(
                    workDataOf(
                        "status" to if (hasPendingWork) "UNAVAILABLE" else "COMPLETE",
                        "error_code" to if (hasPendingWork) "NO_VERIFIED_RETRIEVAL_PACK" else "",
                        "pending" to if (hasPendingWork) 1 else 0,
                        "last_progress_at" to System.currentTimeMillis(),
                    ),
                )
                return@withContext if (CaptionEmbeddingAvailabilityPolicy.shouldRetryForUnavailablePack(hasPendingWork)) {
                    Result.retry()
                } else {
                    Result.success()
                }
            }
        database.prepareCaptionEmbeddingVersion(producer)
        database.materializeCaptionChunkBackfill(BACKFILL_CAPTIONS_PER_RUN)
        val initialCaptionProgress = database.captionEmbeddingProgress()
        if (!database.hasCaptionEmbeddingWork(producer)) {
            try {
                services.captionVectorStore.reconcile(database.currentCaptionEmbeddingChunkIds(producer))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "Caption vector reconciliation failed before completion", error)
                setProgress(
                    workDataOf(
                        "status" to "FAILED",
                        "error_code" to "CAPTION_VECTOR_RECONCILIATION_FAILED",
                        "last_progress_at" to System.currentTimeMillis(),
                    ),
                )
                return@withContext Result.retry()
            }
            setProgress(
                workDataOf(
                    "status" to if (initialCaptionProgress.failedChunkCount > 0) "DEGRADED" else "COMPLETE",
                    "processed" to 0,
                    "failed" to initialCaptionProgress.failedChunkCount,
                    "in_flight" to 0,
                    "last_progress_at" to System.currentTimeMillis(),
                    "next_attempt_at" to 0L,
                    "delayed_retries" to initialCaptionProgress.delayedRetryCount,
                    "quarantined" to initialCaptionProgress.failedChunkCount,
                ),
            )
            return@withContext Result.success()
        }
        val admission = BackgroundWorkAdmissionPolicy(applicationContext).evaluate()
        if (!admission.allowed) return@withContext Result.retry()

        var processed = 0
        var failures = 0
        val progressStartedAt = System.currentTimeMillis()
        val chunks = database.claimCaptionEmbeddingChunks(id.toString(), producer, CHUNKS_PER_RUN)
        if (ForegroundIndexLanePolicy.shouldDeferBackgroundWorker(ForegroundIndexRuntime.active)) {
            database.releaseCaptionEmbeddingClaims(id.toString(), "foreground_index_active")
            return@withContext Result.retry()
        }
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

        val captionProgress = database.captionEmbeddingProgress()
        val estimate = IndexingProgressEstimate.calculate(
            processed = processed,
            remaining = captionProgress.pendingChunkCount + captionProgress.runningChunkCount + captionProgress.delayedRetryCount,
            startedAtMillis = progressStartedAt,
        )
        setProgress(
            workDataOf(
                "processed" to processed,
                "failed" to captionProgress.failedChunkCount,
                "in_flight" to 0,
                "last_progress_at" to System.currentTimeMillis(),
                "next_attempt_at" to 0L,
                "delayed_retries" to captionProgress.delayedRetryCount,
                "quarantined" to captionProgress.failedChunkCount,
                "rate_per_minute" to (estimate.ratePerMinute ?: 0.0),
                "eta_millis" to (estimate.etaMillis ?: 0L),
            ),
        )
        val hasMore = database.hasCaptionEmbeddingWork(producer)
        var reconciliationFailed = false
        if (hasMore && controls.load().captionEmbeddingsEnabled) {
            CaptionEmbeddingScheduler.scheduleContinuation(applicationContext)
        } else if (!hasMore) {
            try {
                services.captionVectorStore.reconcile(database.currentCaptionEmbeddingChunkIds(producer))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                reconciliationFailed = true
                Log.e(TAG, "Caption vector reconciliation failed after processing", error)
                setProgress(
                    workDataOf(
                        "status" to "FAILED",
                        "error_code" to "CAPTION_VECTOR_RECONCILIATION_FAILED",
                        "last_progress_at" to System.currentTimeMillis(),
                    ),
                )
            }
        }
        Log.i(TAG, "Caption embedding run processed=$processed failures=$failures hasMore=$hasMore")
        if (CaptionEmbeddingReconciliationPolicy.shouldRetry(reconciliationFailed, processed, failures)) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    private companion object {
        const val TAG = "AskAlbumCaptionVector"
        const val BACKFILL_CAPTIONS_PER_RUN = 8
        const val CHUNKS_PER_RUN = 24
    }
}

internal object CaptionEmbeddingAvailabilityPolicy {
    fun shouldRetryForUnavailablePack(hasPendingWork: Boolean): Boolean = hasPendingWork
}

internal object CaptionEmbeddingReconciliationPolicy {
    fun shouldRetry(reconciliationFailed: Boolean, processed: Int, failures: Int): Boolean =
        reconciliationFailed || (processed == 0 && failures > 0)
}

object CaptionEmbeddingScheduler {
    private const val UNIQUE_WORK = "gallery-caption-embeddings"

    fun schedule(context: Context) {
        val controls = IndexingJobControlsStore(context).load()
        if (!controls.captionEmbeddingsEnabled || controls.foregroundPaused) return
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request(context))
    }

    fun scheduleContinuation(context: Context, delayMillis: Long = 2_000L) {
        val controls = IndexingJobControlsStore(context).load()
        if (!controls.captionEmbeddingsEnabled || controls.foregroundPaused) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request(context, delayMillis),
        )
    }

    fun restart(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelAllWorkByTag(UNIQUE_WORK).result.get(30, TimeUnit.SECONDS)
        val controls = IndexingJobControlsStore(context).load()
        if (controls.captionEmbeddingsEnabled && !controls.foregroundPaused) {
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
