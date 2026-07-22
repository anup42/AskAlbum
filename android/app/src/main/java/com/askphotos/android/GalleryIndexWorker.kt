package com.askphotos.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Size
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

class GalleryIndexWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val repository = (appContext as AskPhotosApplication).repository

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val labeler = ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        var retryableFailure = false
        try {
            repository.recoverInterruptedJobs()
            val pending = repository.pendingItems(BATCH_SIZE)
            pending.forEach { item ->
                if (isStopped) return@withContext Result.retry()
                repository.markIndexing(item.id)
                runCatching {
                    val analyses = if (item.kind == MediaKind.VIDEO) {
                        VideoKeyframeExtractor(applicationContext).extract(item).map { frame ->
                            analyze(item, frame.bitmap, frame.timestampMs, frame.previewPath, frame.id, frame.visualFeatures, labeler, recognizer)
                        }
                    } else {
                        val (bitmap, previewPath) = prepareBitmap(item)
                        listOf(analyze(item, bitmap, null, previewPath, null, null, labeler, recognizer))
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
                    } finally {
                        analyses.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
                    }
                }.onFailure { error ->
                    val permanent = error is SecurityException || error is java.io.FileNotFoundException
                    repository.failIndex(item.id, error::class.java.simpleName, permanent)
                    retryableFailure = retryableFailure || !permanent
                }
            }
            repository.rebuildEvents()
            if (repository.peopleIndexStatus().enabled) PeopleIndexScheduler.schedule(applicationContext)
            if (repository.pendingItems(1).isNotEmpty()) IndexScheduler.scheduleContinuation(applicationContext)
            if (retryableFailure) Result.retry() else Result.success()
        } finally {
            labeler.close()
            recognizer.close()
        }
    }

    private fun prepareBitmap(item: GalleryItem): Pair<Bitmap, String?> {
        if (item.kind == MediaKind.PDF) return renderPdf(item)
        val uri = Uri.parse(requireNotNull(item.contentUri))
        val bitmap = runCatching {
            applicationContext.contentResolver.loadThumbnail(uri, Size(1024, 1024), null)
        }.getOrNull() ?: applicationContext.contentResolver.openInputStream(uri).use { stream ->
            requireNotNull(BitmapFactory.decodeStream(stream)) { "Unsupported image content" }
        }
        return scaleDown(bitmap) to item.previewPath
    }

    private suspend fun analyze(
        item: GalleryItem,
        bitmap: Bitmap,
        timestampMs: Long?,
        previewPath: String?,
        keyframeId: String?,
        precomputedVisual: VisualFeatures?,
        labeler: com.google.mlkit.vision.label.ImageLabeler,
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
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
                pageIndex = 0,
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

    private fun renderPdf(item: GalleryItem): Pair<Bitmap, String> {
        val uri = Uri.parse(requireNotNull(item.contentUri))
        val descriptor = requireNotNull(applicationContext.contentResolver.openFileDescriptor(uri, "r"))
        descriptor.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                require(renderer.pageCount > 0) { "PDF has no pages" }
                renderer.openPage(0).use { page ->
                    val scale = (1400f / page.width).coerceAtMost(2f)
                    val bitmap = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val previewDir = File(applicationContext.filesDir, "previews").apply { mkdirs() }
                    val preview = File(previewDir, "${item.id}.png")
                    preview.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 92, it) }
                    return bitmap to preview.absolutePath
                }
            }
        }
    }

    private fun scaleDown(source: Bitmap): Bitmap {
        val maxSide = maxOf(source.width, source.height)
        if (maxSide <= 1280) return source
        val scale = 1280f / maxSide
        val scaled = Bitmap.createScaledBitmap(source, (source.width * scale).toInt(), (source.height * scale).toInt(), true)
        if (scaled !== source) source.recycle()
        return scaled
    }

    private companion object {
        const val BATCH_SIZE = 24
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
}
