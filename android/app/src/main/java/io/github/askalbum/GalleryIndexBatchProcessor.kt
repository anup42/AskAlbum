package io.github.anup42.askalbum

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Size
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.io.FileNotFoundException

internal class GalleryIndexBatchProcessor(
    context: Context,
    private val repository: GalleryRepository,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
    private val ocrRegistry = (appContext as AskAlbumApplication).services.ocrEngines
    private var ocrLease: ModelEngineLease<OcrEngine>? = null

    suspend fun processBatch(
        allowedMediaIds: Set<String>? = null,
        limit: Int = DEFAULT_BATCH_SIZE,
        rebuildEvents: Boolean = true,
        ownerId: String = "gallery-index",
        canContinue: () -> Boolean = { true },
    ): IndexBatchResult {
        require(limit in 1..MAX_BATCH_SIZE) { "Gallery index batch is out of bounds" }
        val pending = pendingItems(allowedMediaIds, limit)
        if (pending.isEmpty()) return IndexBatchResult(processed = 0, hasMore = false)
        var processed = 0
        var retryableFailures = 0
        var permanentFailures = 0
        var stopped = false
        for (item in pending) {
            if (!canContinue()) {
                stopped = true
                break
            }
            if (!repository.markIndexing(item.id, ownerId)) continue
            try {
                val analyses = when (item.kind) {
                    MediaKind.VIDEO -> VideoKeyframeExtractor(appContext).extract(item).map { frame ->
                        analyze(item, frame.bitmap, 0, frame.timestampMs, frame.previewPath, frame.id, frame.visualFeatures)
                    }
                    MediaKind.PDF -> PdfPageRenderer(appContext).render(item).map { page ->
                        analyze(item, page.bitmap, page.pageIndex, null, page.previewPath, null, null)
                    }
                    MediaKind.IMAGE -> {
                        val (bitmap, previewPath) = prepareBitmap(item)
                        listOf(analyze(item, bitmap, 0, null, previewPath, null, null))
                    }
                }
                val labels = analyses.flatMap { it.labels }.distinct().take(24)
                val blocks = analyses.flatMap { it.blocks }
                val entities = DocumentFactExtractor.extract(blocks)
                val representative = analyses.maxByOrNull { it.visualFeatures.qualityScore }
                    ?: error("No media frame was analyzed")
                try {
                    repository.completeIndex(
                        id = item.id,
                        labels = labels,
                        description = labels.take(8).joinToString(", "),
                        ocrText = analyses.map { it.ocrText }.filter(String::isNotBlank).joinToString("\n"),
                        faceCount = 0,
                        previewPath = representative.previewPath,
                        blocks = blocks,
                        entities = entities,
                        ocrAttempted = analyses.any { it.ocrAttempted },
                        ocrProducerVersion = analyses.firstNotNullOfOrNull { it.ocrProducerVersion },
                        visualFeatures = representative.visualFeatures,
                        keyframes = analyses.mapNotNull { it.asKeyframe(item.id) },
                    )
                    processed++
                } finally {
                    analyses.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val permanent = error is SecurityException || error is FileNotFoundException
                when (repository.failIndex(item.id, error::class.java.simpleName, permanent)) {
                    StageStatus.FAILED_RETRYABLE -> retryableFailures++
                    else -> permanentFailures++
                }
            }
        }
        if (processed > 0 && rebuildEvents) repository.rebuildEvents()
        return IndexBatchResult(
            processed = processed,
            hasMore = pendingItems(allowedMediaIds, 1).isNotEmpty(),
            retryableFailures = retryableFailures,
            permanentFailures = permanentFailures,
            stopped = stopped,
            nextAttemptAtMillis = repository.nextMediaRetryAt(),
        )
    }

    override fun close() {
        labeler.close()
        ocrLease?.close()
        ocrLease = null
    }

    private fun pendingItems(allowedMediaIds: Set<String>?, limit: Int): List<GalleryItem> {
        if (allowedMediaIds?.isEmpty() == true) return emptyList()
        return if (allowedMediaIds == null) repository.pendingItems(limit)
        else repository.pendingItemsForIds(allowedMediaIds, limit)
    }

    private fun prepareBitmap(item: GalleryItem): Pair<Bitmap, String?> {
        val uri = Uri.parse(requireNotNull(item.contentUri))
        val bitmap = runCatching {
            appContext.contentResolver.loadThumbnail(uri, Size(1024, 1024), null)
        }.getOrNull() ?: appContext.contentResolver.openInputStream(uri).use { stream ->
            requireNotNull(BitmapFactory.decodeStream(stream)) { "Unsupported image content" }
        }
        return scaleDown(bitmap) to item.previewPath
    }

    private suspend fun analyze(
        item: GalleryItem,
        bitmap: Bitmap,
        pageIndex: Int,
        timestampMs: Long?,
        previewPath: String?,
        keyframeId: String?,
        precomputedVisual: VisualFeatures?,
    ): FrameAnalysis {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val visual = precomputedVisual ?: VisualFeatureExtractor.extract(pixels, bitmap.width, bitmap.height)
        val input = InputImage.fromBitmap(bitmap, 0)
        val labels = labeler.process(input).await()
            .filter { it.confidence >= .55f }
            .sortedByDescending { it.confidence }
            .take(12)
            .map { it.text.lowercase() }
            .distinct()
        val ocrDecision = OcrLikelihoodGate.decide(item, labels, pixels, bitmap.width, bitmap.height)
        val ocr = if (ocrDecision.shouldRun) {
            val lease = ocrLease ?: ocrRegistry.acquire().also { ocrLease = it }
            lease to lease.engine.recognize(bitmap.toModelImage())
        } else null
        val blocks = ocr?.second?.blocks.orEmpty().mapNotNull { block ->
            if (block.bounds.size != 4 || block.bounds[0] >= block.bounds[2] || block.bounds[1] >= block.bounds[3]) return@mapNotNull null
            OcrBlockRecord(
                text = block.text,
                normalizedText = block.text.lowercase().replace(Regex("\\s+"), " ").trim(),
                language = block.script ?: ocr?.second?.language,
                pageIndex = pageIndex,
                timestampMs = timestampMs,
                confidence = block.confidence,
                left = block.bounds[0],
                top = block.bounds[1],
                right = block.bounds[2],
                bottom = block.bounds[3],
            )
        }
        return FrameAnalysis(
            bitmap = bitmap,
            timestampMs = timestampMs,
            previewPath = previewPath,
            keyframeId = keyframeId,
            labels = labels,
            ocrText = blocks.joinToString("\n") { it.text },
            blocks = blocks,
            ocrAttempted = ocrDecision.shouldRun,
            ocrProducerVersion = ocr?.first?.descriptor?.producerVersion,
            visualFeatures = visual,
        )
    }

    private fun scaleDown(source: Bitmap): Bitmap {
        val maxSide = maxOf(source.width, source.height)
        if (maxSide <= 1280) return source
        val scale = 1280f / maxSide
        val scaled = Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt(),
            (source.height * scale).toInt(),
            true,
        )
        if (scaled !== source) source.recycle()
        return scaled
    }

    private fun Bitmap.toModelImage(): ModelImage {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        val rgb = ByteArray(pixels.size * 3)
        pixels.forEachIndexed { index, pixel ->
            rgb[index * 3] = android.graphics.Color.red(pixel).toByte()
            rgb[index * 3 + 1] = android.graphics.Color.green(pixel).toByte()
            rgb[index * 3 + 2] = android.graphics.Color.blue(pixel).toByte()
        }
        return ModelImage(rgb, width, height)
    }

    private data class FrameAnalysis(
        val bitmap: Bitmap,
        val timestampMs: Long?,
        val previewPath: String?,
        val keyframeId: String?,
        val labels: List<String>,
        val ocrText: String,
        val blocks: List<OcrBlockRecord>,
        val ocrAttempted: Boolean,
        val ocrProducerVersion: String?,
        val visualFeatures: VisualFeatures,
    ) {
        fun asKeyframe(mediaId: String): VideoKeyframeRecord? {
            val id = keyframeId ?: return null
            return VideoKeyframeRecord(
                id = id,
                mediaId = mediaId,
                timestampMs = requireNotNull(timestampMs),
                previewPath = requireNotNull(previewPath),
                labels = labels,
                ocrText = ocrText,
                perceptualHash = visualFeatures.perceptualHash,
                qualityScore = visualFeatures.qualityScore,
                producerVersion = VideoKeyframePolicy.PRODUCER_VERSION,
            )
        }
    }

    companion object {
        const val DEFAULT_BATCH_SIZE = 24
        private const val MAX_BATCH_SIZE = 64
    }
}
