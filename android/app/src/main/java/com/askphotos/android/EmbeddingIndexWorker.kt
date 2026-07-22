package com.askphotos.android

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Size
import androidx.work.Constraints
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.File
import java.util.concurrent.TimeUnit

class EmbeddingIndexWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private val app = appContext as AskPhotosApplication
    private val repository = app.repository
    private val packs = app.services.retrievalModelPackManager
    private val vectors = app.services.semanticVectorStore
    private val engine = app.services.embeddingEngine
    private val workAdmission = BackgroundWorkAdmissionPolicy(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!workAdmission.evaluate().allowed) return@withContext Result.retry()
        val producer = vectors.producerVersion() ?: return@withContext Result.success()
        repository.recoverInterruptedJobs()
        val activityManager = applicationContext.getSystemService(ActivityManager::class.java)
        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val batchSize = EmbeddingBatchPolicy.forDevice(
            memoryClassMb = activityManager.memoryClass,
            totalRamMb = (memoryInfo.totalMem / (1024L * 1024L)).toInt(),
        )
        val candidates = repository.embeddingPendingItems(producer, batchSize)
        val keyframeCandidates = repository.keyframeEmbeddingPendingItems(producer, KEYFRAME_BATCH_SIZE)
        if (candidates.isEmpty() && keyframeCandidates.isEmpty()) {
            vectors.reconcile(repository.accessibleVectorIds())
            return@withContext Result.success()
        }

        val prepared = mutableListOf<Pair<GalleryItem, ModelImage>>()
        candidates.forEach { item ->
            if (isStopped || !workAdmission.evaluate().allowed) return@withContext Result.retry()
            repository.markEmbedding(item.id, producer)
            runCatching { decodeModelImage(item) }
                .onSuccess { prepared += item to it }
                .onFailure { error ->
                    repository.failEmbedding(
                        item.id,
                        producer,
                        error::class.java.simpleName,
                        error is SecurityException || error is FileNotFoundException || error is IllegalArgumentException,
                    )
                }
        }

        if (prepared.isNotEmpty()) {
            if (!workAdmission.evaluate().allowed) {
                repository.recoverInterruptedJobs()
                return@withContext Result.retry()
            }
            val embedded = runCatching {
                val images = prepared.map { it.second }
                (engine as? LiteRtImageTextEmbeddingEngine)?.embedImages(images)
                    ?: images.map { engine.embedImage(it) }
            }.getOrElse { error ->
                prepared.forEach { (item) -> repository.failEmbedding(item.id, producer, error::class.java.simpleName, false) }
                return@withContext Result.retry()
            }
            prepared.zip(embedded).forEach { (entry, vector) ->
                val item = entry.first
                runCatching { vectors.upsert(item.id, vector) }
                    .onSuccess { repository.completeEmbedding(item.id, producer) }
                    .onFailure { error -> repository.failEmbedding(item.id, producer, error::class.java.simpleName, false) }
            }
        }

        if (keyframeCandidates.isNotEmpty()) {
            if (!workAdmission.evaluate().allowed) return@withContext Result.retry()
            val preparedFrames = keyframeCandidates.map { frame -> frame to decodeKeyframeModelImage(frame) }
            val embedded = runCatching {
                val images = preparedFrames.map { it.second }
                (engine as? LiteRtImageTextEmbeddingEngine)?.embedImages(images)
                    ?: images.map { engine.embedImage(it) }
            }.getOrElse { return@withContext Result.retry() }
            preparedFrames.zip(embedded).forEach { (entry, vector) ->
                val frame = entry.first
                runCatching { vectors.upsert(frame.id, vector) }
                    .onSuccess { repository.completeKeyframeEmbedding(frame.id, producer) }
                    .onFailure { return@withContext Result.retry() }
            }
        }

        if (
            repository.embeddingPendingItems(producer, 1).isNotEmpty() ||
            repository.keyframeEmbeddingPendingItems(producer, 1).isNotEmpty()
        ) {
            EmbeddingIndexScheduler.scheduleContinuation(applicationContext)
        } else {
            vectors.reconcile(repository.accessibleVectorIds())
        }
        Result.success()
    }

    private fun decodeModelImage(item: GalleryItem): ModelImage {
        val bitmap = when {
            item.assetPath != null -> applicationContext.assets.open(item.assetPath).use {
                requireNotNull(BitmapFactory.decodeStream(it)) { "Unsupported bundled image" }
            }
            item.kind == MediaKind.PDF -> renderFirstPdfPage(item)
            else -> {
                val uri = Uri.parse(requireNotNull(item.contentUri))
                applicationContext.contentResolver.loadThumbnail(uri, Size(512, 512), null)
            }
        }
        return bitmap.useAsModelImage()
    }

    private fun renderFirstPdfPage(item: GalleryItem): Bitmap {
        val uri = Uri.parse(requireNotNull(item.contentUri))
        return requireNotNull(applicationContext.contentResolver.openFileDescriptor(uri, "r")).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                require(renderer.pageCount > 0) { "PDF has no pages" }
                renderer.openPage(0).use { page ->
                    val scale = (512f / maxOf(page.width, page.height)).coerceAtMost(1f)
                    Bitmap.createBitmap(
                        maxOf(1, (page.width * scale).toInt()),
                        maxOf(1, (page.height * scale).toInt()),
                        Bitmap.Config.ARGB_8888,
                    ).also { target ->
                        target.eraseColor(android.graphics.Color.WHITE)
                        page.render(target, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }

    private fun decodeKeyframeModelImage(frame: VideoKeyframeRecord): ModelImage {
        val root = File(applicationContext.filesDir, "video-keyframes").canonicalFile
        val file = File(frame.previewPath).canonicalFile
        require(file.toPath().startsWith(root.toPath()) && file.isFile) { "Keyframe preview is unavailable" }
        val bitmap = requireNotNull(BitmapFactory.decodeFile(file.absolutePath)) { "Keyframe preview is invalid" }
        return bitmap.useAsModelImage()
    }

    private fun Bitmap.useAsModelImage(): ModelImage = try {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        val rgb = ByteArray(pixels.size * 3)
        pixels.forEachIndexed { index, color ->
            rgb[index * 3] = (color shr 16).toByte()
            rgb[index * 3 + 1] = (color shr 8).toByte()
            rgb[index * 3 + 2] = color.toByte()
        }
        ModelImage(rgb, width, height)
    } finally {
        if (!isRecycled) recycle()
    }

    private companion object {
        const val KEYFRAME_BATCH_SIZE = 8
    }
}

internal object EmbeddingBatchPolicy {
    fun forDevice(memoryClassMb: Int, totalRamMb: Int): Int = when {
        memoryClassMb <= 192 || totalRamMb < 4_096 -> 4
        memoryClassMb <= 256 || totalRamMb < 6_144 -> 12
        else -> 24
    }
}

object EmbeddingIndexScheduler {
    private const val UNIQUE_WORK = "gallery-image-embeddings"

    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.KEEP, request())
    }

    fun scheduleContinuation(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request(),
        )
    }

    fun restart(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelAllWorkByTag(UNIQUE_WORK).result.get(30, TimeUnit.SECONDS)
        workManager.enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request())
    }

    private fun request() = OneTimeWorkRequestBuilder<EmbeddingIndexWorker>()
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build(),
        )
        .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
        .addTag(UNIQUE_WORK)
        .build()
}
