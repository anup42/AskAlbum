package io.github.anup42.askalbum

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Size
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileNotFoundException

internal class EmbeddingIndexBatchProcessor(
    context: Context,
    private val repository: GalleryRepository,
    private val vectors: SemanticVectorStore,
    private val engine: ImageTextEmbeddingEngine,
) {
    private val appContext = context.applicationContext
    private val activityManager = appContext.getSystemService(ActivityManager::class.java)
    private val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
    val batchSize = EmbeddingBatchPolicy.forDevice(
        memoryClassMb = activityManager.memoryClass,
        totalRamMb = (memoryInfo.totalMem / (1024L * 1024L)).toInt(),
    )

    suspend fun processBatch(
        allowedMediaIds: Set<String>? = null,
        ownerId: String = "gallery-image-embeddings",
        canContinue: () -> Boolean = { true },
    ): IndexBatchResult {
        val producer = vectors.producerVersion() ?: return IndexBatchResult(processed = 0, hasMore = false)
        val candidates = pendingItems(producer, allowedMediaIds, batchSize)
        val keyframeCandidates = pendingKeyframes(producer, allowedMediaIds, KEYFRAME_BATCH_SIZE)
        if (candidates.isEmpty() && keyframeCandidates.isEmpty()) {
            if (allowedMediaIds == null) vectors.reconcile(repository.accessibleVectorIds())
            return IndexBatchResult(processed = 0, hasMore = false)
        }

        var processed = 0
        var retryableFailures = 0
        var permanentFailures = 0
        val prepared = mutableListOf<Pair<GalleryItem, ModelImage>>()
        for (item in candidates) {
            if (!canContinue()) {
                repository.recoverInterruptedJobs()
                return result(producer, allowedMediaIds, processed, retryableFailures, permanentFailures, stopped = true)
            }
            if (!repository.markEmbedding(item.id, producer, ownerId)) continue
            try {
                prepared += item to decodeModelImage(item)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val permanent = error is SecurityException || error is FileNotFoundException || error is IllegalArgumentException
                when (repository.failEmbedding(item.id, producer, error::class.java.simpleName, permanent)) {
                    StageStatus.FAILED_RETRYABLE -> retryableFailures++
                    else -> permanentFailures++
                }
            }
        }

        if (prepared.isNotEmpty()) {
            if (!canContinue()) {
                repository.recoverInterruptedJobs()
                return result(producer, allowedMediaIds, processed, retryableFailures, permanentFailures, stopped = true)
            }
            val embedded = try {
                val images = prepared.map { it.second }
                (engine as? LiteRtImageTextEmbeddingEngine)?.embedImages(images)
                    ?: images.map { engine.embedImage(it) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                prepared.forEach { (item) ->
                    repository.failEmbedding(item.id, producer, error::class.java.simpleName, false)
                }
                return result(
                    producer,
                    allowedMediaIds,
                    processed,
                    retryableFailures + prepared.size,
                    permanentFailures,
                )
            }
            prepared.zip(embedded).forEach { (entry, vector) ->
                val item = entry.first
                try {
                    vectors.upsert(item.id, vector)
                    repository.completeEmbedding(item.id, producer)
                    processed++
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    when (repository.failEmbedding(item.id, producer, error::class.java.simpleName, false)) {
                        StageStatus.FAILED_RETRYABLE -> retryableFailures++
                        else -> permanentFailures++
                    }
                }
            }
        }

        if (keyframeCandidates.isNotEmpty()) {
            if (!canContinue()) {
                return result(producer, allowedMediaIds, processed, retryableFailures, permanentFailures, stopped = true)
            }
            try {
                val preparedFrames = keyframeCandidates.map { frame -> frame to decodeKeyframeModelImage(frame) }
                val images = preparedFrames.map { it.second }
                val embedded = (engine as? LiteRtImageTextEmbeddingEngine)?.embedImages(images)
                    ?: images.map { engine.embedImage(it) }
                preparedFrames.zip(embedded).forEach { (entry, vector) ->
                    vectors.upsert(entry.first.id, vector)
                    repository.completeKeyframeEmbedding(entry.first.id, producer)
                    processed++
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                retryableFailures += keyframeCandidates.size
            }
        }

        val result = result(producer, allowedMediaIds, processed, retryableFailures, permanentFailures)
        if (!result.hasMore && allowedMediaIds == null) vectors.reconcile(repository.accessibleVectorIds())
        return result
    }

    private fun result(
        producer: String,
        allowedMediaIds: Set<String>?,
        processed: Int,
        retryableFailures: Int,
        permanentFailures: Int,
        stopped: Boolean = false,
    ) = IndexBatchResult(
        processed = processed,
        hasMore = pendingItems(producer, allowedMediaIds, 1).isNotEmpty() ||
            pendingKeyframes(producer, allowedMediaIds, 1).isNotEmpty(),
        retryableFailures = retryableFailures,
        permanentFailures = permanentFailures,
        stopped = stopped,
        nextAttemptAtMillis = repository.nextEmbeddingRetryAt(),
    )

    private fun pendingItems(producer: String, allowedMediaIds: Set<String>?, limit: Int): List<GalleryItem> {
        if (allowedMediaIds?.isEmpty() == true) return emptyList()
        return if (allowedMediaIds == null) repository.embeddingPendingItems(producer, limit)
        else repository.embeddingPendingItemsForIds(producer, allowedMediaIds, limit)
    }

    private fun pendingKeyframes(
        producer: String,
        allowedMediaIds: Set<String>?,
        limit: Int,
    ): List<VideoKeyframeRecord> {
        if (allowedMediaIds?.isEmpty() == true) return emptyList()
        return if (allowedMediaIds == null) repository.keyframeEmbeddingPendingItems(producer, limit)
        else repository.keyframeEmbeddingPendingItemsForIds(producer, allowedMediaIds, limit)
    }

    private fun decodeModelImage(item: GalleryItem): ModelImage {
        val bitmap = when {
            item.assetPath != null -> appContext.assets.open(item.assetPath).use {
                requireNotNull(BitmapFactory.decodeStream(it)) { "Unsupported bundled image" }
            }
            item.kind == MediaKind.PDF -> renderFirstPdfPage(item)
            else -> {
                val uri = Uri.parse(requireNotNull(item.contentUri))
                appContext.contentResolver.loadThumbnail(uri, Size(512, 512), null)
            }
        }
        return bitmap.useAsModelImage()
    }

    private fun renderFirstPdfPage(item: GalleryItem): Bitmap {
        val uri = Uri.parse(requireNotNull(item.contentUri))
        return requireNotNull(appContext.contentResolver.openFileDescriptor(uri, "r")).use { descriptor ->
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
        val root = File(appContext.filesDir, "video-keyframes").canonicalFile
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
