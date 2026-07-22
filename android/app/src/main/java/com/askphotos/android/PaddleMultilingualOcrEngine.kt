package com.askphotos.android

import android.content.Context
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.Closeable

class PaddleMultilingualOcrEngine private constructor(
    private val delegates: List<Delegate>,
) : OcrEngine, Closeable {
    override suspend fun recognize(image: ModelImage): OcrDocument {
        val bitmap = image.toRgbBitmap()
        return try {
            val candidates = delegates.flatMap { delegate ->
                delegate.engine.recognize(bitmap).results.mapNotNull { result ->
                    val points = result.box.points
                    val left = points.minOf { it.x }.div(image.width).coerceIn(0f, 1f)
                    val top = points.minOf { it.y }.div(image.height).coerceIn(0f, 1f)
                    val right = points.maxOf { it.x }.div(image.width).coerceIn(0f, 1f)
                    val bottom = points.maxOf { it.y }.div(image.height).coerceIn(0f, 1f)
                    if (result.text.isBlank() || left >= right || top >= bottom) null else Candidate(
                        OcrBlock(result.text.trim(), result.confidence.coerceIn(0f, 1f), listOf(left, top, right, bottom), delegate.script),
                        adjustedScore(result.text, result.confidence, delegate.script),
                    )
                }
            }
            val merged = mergeOverlaps(candidates).sortedWith(compareBy<OcrBlock> { it.bounds[1] }.thenBy { it.bounds[0] })
            OcrDocument(merged, language = "mul-Latn-Deva")
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() {
        runBlocking(Dispatchers.IO) { delegates.forEach { it.engine.release() } }
    }

    private fun mergeOverlaps(candidates: List<Candidate>): List<OcrBlock> {
        val selected = mutableListOf<Candidate>()
        candidates.sortedByDescending(Candidate::score).forEach { candidate ->
            if (selected.none { overlap(it.block.bounds, candidate.block.bounds) >= OVERLAP_THRESHOLD }) selected += candidate
        }
        return selected.map(Candidate::block)
    }

    private fun adjustedScore(text: String, confidence: Float, script: String): Float {
        val detected = unicodeScript(text)
        return confidence + when {
            detected == script -> .12f
            detected != null -> -.25f
            else -> 0f
        }
    }

    private fun overlap(a: List<Float>, b: List<Float>): Float {
        val intersection = (minOf(a[2], b[2]) - maxOf(a[0], b[0])).coerceAtLeast(0f) *
            (minOf(a[3], b[3]) - maxOf(a[1], b[1])).coerceAtLeast(0f)
        val smaller = minOf((a[2] - a[0]) * (a[3] - a[1]), (b[2] - b[0]) * (b[3] - b[1]))
        return if (smaller <= 0f) 0f else intersection / smaller
    }

    private data class Delegate(val script: String, val engine: PaddleOCR)
    private data class Candidate(val block: OcrBlock, val score: Float)

    companion object {
        private const val OVERLAP_THRESHOLD = .55f

        suspend fun create(context: Context, pack: InstalledOcrModelPack): PaddleMultilingualOcrEngine {
            require(OpenCVUtils.init(context)) { "OpenCV could not initialize for PaddleOCR" }
            val config = PaddleOCRConfig(recScoreThresh = .25f, recBatchSize = 1)
            val engineConfig = EngineConfig(numThreads = 2)
            val latin = PaddleOCR.create(context, config, engineConfig, pack.detector, pack.latinRecognizer, pack.latinConfig)
            val devanagari = runCatching {
                PaddleOCR.create(context, config, engineConfig, pack.detector, pack.devanagariRecognizer, pack.devanagariConfig)
            }.getOrElse { error ->
                latin.release()
                throw error
            }
            return PaddleMultilingualOcrEngine(listOf(Delegate("Latn", latin), Delegate("Deva", devanagari)))
        }
    }
}
