package com.samsung.agenticgallery

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class EmbeddingIndexWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val app = appContext as AgenticGalleryApplication
    private val repository = app.repository
    private val workAdmission = BackgroundWorkAdmissionPolicy(appContext)
    private val jobControls = IndexingJobControlsStore(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!jobControls.load().embeddingsEnabled) return@withContext Result.success()
        if (!workAdmission.evaluate().allowed) return@withContext Result.retry()
        repository.recoverInterruptedJobs()
        val processor = EmbeddingIndexBatchProcessor(
            context = applicationContext,
            repository = repository,
            vectors = app.services.semanticVectorStore,
            engine = app.services.embeddingEngine,
        )
        val inferenceThreads = (app.services.embeddingEngine as? LiteRtImageTextEmbeddingEngine)
            ?.onnxInferenceThreads
        Log.i(
            TAG,
            "SigLIP2 embedding worker started batchSize=${processor.batchSize} onnxThreads=${inferenceThreads ?: "runtime"}",
        )
        val budget = IndexingWorkerRunBudget()
        var batches = 0
        var processed = 0
        var retryableFailures = 0
        var permanentFailures = 0
        var hasMore = true
        var stoppedDuringBatch = false
        while (
            hasMore &&
            budget.hasTimeRemaining() &&
            !isStopped &&
            jobControls.load().embeddingsEnabled &&
            workAdmission.evaluate().allowed
        ) {
            val batch = IndexingResourceCoordinator.withBackgroundPermit {
                processor.processBatch(
                    ownerId = id.toString(),
                    canContinue = {
                        !isStopped &&
                            jobControls.load().embeddingsEnabled &&
                            workAdmission.evaluate().allowed
                    },
                )
            }
            batches++
            processed += batch.processed
            retryableFailures += batch.retryableFailures
            permanentFailures += batch.permanentFailures
            hasMore = batch.hasMore
            stoppedDuringBatch = batch.stopped
            setProgress(
                workDataOf(
                    "processed" to processed,
                    "batches" to batches,
                    "in_flight" to if (hasMore) processor.batchSize else 0,
                    "retryable_failures" to retryableFailures,
                ),
            )
            if (batch.stopped) break
        }

        val enabled = jobControls.load().embeddingsEnabled
        val admission = workAdmission.evaluate()
        val shouldRetry = IndexingWorkerResultPolicy.shouldRetryWorker(
            processed = processed,
            retryableFailures = retryableFailures,
            stopped = isStopped || stoppedDuringBatch,
            admissionAllowed = admission.allowed,
            hasImmediateWork = hasMore,
        )
        if (hasMore && enabled && !shouldRetry) {
            EmbeddingIndexScheduler.scheduleContinuation(applicationContext)
        } else if (!hasMore && enabled) {
            repository.nextEmbeddingRetryAt()?.let { retryAt ->
                EmbeddingIndexScheduler.scheduleContinuation(
                    applicationContext,
                    (retryAt - System.currentTimeMillis()).coerceAtLeast(10_000L),
                )
            }
        }
        Log.i(
            TAG,
            "SigLIP2 embedding run finished batches=$batches processed=$processed hasMore=$hasMore " +
                "stopped=${isStopped || stoppedDuringBatch} retryableFailures=$retryableFailures " +
                "permanentFailures=$permanentFailures elapsedMs=${budget.elapsedMillis()}",
        )
        when {
            !enabled -> Result.success()
            shouldRetry -> Result.retry()
            else -> Result.success()
        }
    }

    private companion object {
        const val TAG = "AgenticGalleryEmbedding"
    }
}

internal object EmbeddingBatchPolicy {
    fun forDevice(memoryClassMb: Int, totalRamMb: Int): Int = when {
        memoryClassMb <= 192 || totalRamMb < 4_096 -> 4
        totalRamMb < 6_144 -> 12
        totalRamMb < 8_192 -> 24
        else -> 32
    }
}

object EmbeddingIndexScheduler {
    private const val UNIQUE_WORK = "gallery-image-embeddings"

    fun schedule(context: Context) {
        if (!IndexingJobControlsStore(context).load().embeddingsEnabled) return
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request(context))
    }

    fun scheduleContinuation(context: Context, initialDelayMillis: Long = 0L) {
        if (!IndexingJobControlsStore(context).load().embeddingsEnabled) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request(context, initialDelayMillis),
        )
    }

    fun restart(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelAllWorkByTag(UNIQUE_WORK).result.get(30, TimeUnit.SECONDS)
        if (IndexingJobControlsStore(context).load().embeddingsEnabled) {
            workManager.enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request(context))
        }
    }

    fun cancelAndWait(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(UNIQUE_WORK).result.get(30, TimeUnit.SECONDS)
    }

    private fun request(context: Context, initialDelayMillis: Long = 0L) =
        OneTimeWorkRequestBuilder<EmbeddingIndexWorker>()
            .setConstraints(indexingWorkerConstraints(context))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .addTag(UNIQUE_WORK)
            .apply {
                if (initialDelayMillis > 0L) setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            }
            .build()
}
