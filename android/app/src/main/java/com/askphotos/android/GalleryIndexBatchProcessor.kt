package com.askphotos.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Size
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.io.FileNotFoundException

internal class GalleryIndexBatchProcessor(
    context: Context,
    private val repository: GalleryRepository,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun processBatch(
        allowedMediaIds: Set<String>? = null,
        limit: Int = DEFAULT_BATCH_SIZE,
        rebuildEvents: Boolean = true,
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
            repository.markIndexing(item.id)
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
                repository.failIndex(item.id, error::class.java.simpleName, permanent)
                if (permanent) permanentFailures++ else retryableFailures++
            }
        }
        if (processed > 0 && rebuildEvents) repository.rebuildEvents()
        return IndexBatchResult(
            processed = processed,
            hasMore = pendingItems(allowedMediaIds, 1).isNotEmpty(),
            retryableFailures = retryableFailures,
            permanentFailures = permanentFailures,
            stopped = stopped,
        )
    }

    override fun close() {
        labeler.close()
        recognizer.close()
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
        val text = if (ocrDecision.shouldRun) recognizer.process(input).await() else null
        val blocks = text?.textBlocks.orEmpty().mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            OcrBlockRecord(
                text = block.text,
                normalizedText = block.text.lowercase().replace(Regex("\\s+"), " ").trim(),
                language = block.recognizedLanguage.takeUnless(String::isBlank),
                pageIndex = pageIndex,
                timestampMs = timestampMs,
                confidence = block.lines.mapNotNull { it.confidence }.average().takeUnless(Double::isNaN)?.toFloat() ?: .8f,
                left = box.left.toFloat() / bitmap.width,
                top = box.top.toFloat() / bitmap.height,
                right = box.right.toFloat() / bitmap.width,
                bottom = box.bottom.toFloat() / bitmap.height,
            )
        }
        return FrameAnalysis(
            bitmap = bitmap,
            timestampMs = timestampMs,
            previewPath = previewPath,
            keyframeId = keyframeId,
            labels = labels,
            ocrText = text?.text.orEmpty(),
            blocks = blocks,
            ocrAttempted = ocrDecision.shouldRun,
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

    private data class FrameAnalysis(
        val bitmap: Bitmap,
        val timestampMs: Long?,
        val previewPath: String?,
        val keyframeId: String?,
        val labels: List<String>,
        val ocrText: String,
        val blocks: List<OcrBlockRecord>,
        val ocrAttempted: Boolean,
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
