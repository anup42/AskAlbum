package io.github.anup42.askalbum

import android.content.Context
import android.graphics.Bitmap
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.engine.ORTSessionManager
import com.paddle.ocr.model.ModelConfig
import com.paddle.ocr.postprocess.CTCDecoder
import java.io.Closeable
import kotlin.math.ceil
import kotlin.math.round

/** PP-OCRv5 inference with Android Bitmap/Kotlin preprocessing and no OpenCV dependency. */
class PaddleMultilingualOcrEngine private constructor(
    private val latinSession: ORTSessionManager,
    private val devanagariSession: ORTSessionManager,
    private val latinCharacters: List<String>,
    private val devanagariCharacters: List<String>,
) : OcrEngine, Closeable {
    override suspend fun recognize(image: ModelImage): OcrDocument {
        val bitmap = image.toRgbBitmap()
        return try {
            val detectorInput = PaddleBitmapPipeline.detectionTensor(bitmap)
            val (probabilities, outputShape) = latinSession.runDetection(detectorInput.data, detectorInput.shape)
            val boxes = PaddleDbPostProcessor.extract(
                probabilities = probabilities,
                outputShape = outputShape,
                originalWidth = bitmap.width,
                originalHeight = bitmap.height,
            )
            val blocks = boxes.mapNotNull { box ->
                val crop = Bitmap.createBitmap(bitmap, box.left, box.top, box.width, box.height)
                try {
                    val tensor = PaddleBitmapPipeline.recognitionTensor(crop)
                    val latin = recognizeLine(latinSession, tensor, latinCharacters, "Latn")
                    val devanagari = recognizeLine(devanagariSession, tensor, devanagariCharacters, "Deva")
                    val selected = listOf(latin, devanagari).maxByOrNull(LineCandidate::score)
                        ?.takeIf { it.text.isNotBlank() && it.confidence >= MIN_RECOGNITION_CONFIDENCE }
                        ?: return@mapNotNull null
                    OcrBlock(
                        text = selected.text.trim(),
                        confidence = selected.confidence.coerceIn(0f, 1f),
                        bounds = listOf(
                            box.left.toFloat() / bitmap.width,
                            box.top.toFloat() / bitmap.height,
                            box.right.toFloat() / bitmap.width,
                            box.bottom.toFloat() / bitmap.height,
                        ),
                        script = unicodeScript(selected.text) ?: selected.script,
                    )
                } finally {
                    crop.recycle()
                }
            }
            OcrDocument(blocks, language = "mul-Latn-Deva")
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() {
        try {
            devanagariSession.release()
        } finally {
            latinSession.release()
        }
    }

    private fun recognizeLine(
        session: ORTSessionManager,
        tensor: PaddleTensor,
        characters: List<String>,
        script: String,
    ): LineCandidate {
        val (output, shape) = session.runRecognition(tensor.data, tensor.shape)
        val (text, confidence) = CTCDecoder.decode(output, shape, characters).single()
        val detected = unicodeScript(text)
        val score = confidence + when {
            detected == script -> SCRIPT_MATCH_BONUS
            detected != null -> SCRIPT_MISMATCH_PENALTY
            script == "Latn" -> LATIN_DEFAULT_BONUS
            else -> 0f
        }
        return LineCandidate(text, confidence, script, score)
    }

    private data class LineCandidate(
        val text: String,
        val confidence: Float,
        val script: String,
        val score: Float,
    )

    companion object {
        private const val MIN_RECOGNITION_CONFIDENCE = .25f
        private const val SCRIPT_MATCH_BONUS = .12f
        private const val SCRIPT_MISMATCH_PENALTY = -.25f
        private const val LATIN_DEFAULT_BONUS = .02f

        suspend fun create(context: Context, pack: InstalledOcrModelPack): PaddleMultilingualOcrEngine {
            val engineConfig = EngineConfig(numThreads = 2)
            val latin = ORTSessionManager(context, engineConfig)
            val devanagari = ORTSessionManager(context, engineConfig)
            try {
                latin.loadModelFiles(pack.detector, pack.latinRecognizer)
                devanagari.loadModelFiles(pack.detector, pack.devanagariRecognizer)
                return PaddleMultilingualOcrEngine(
                    latinSession = latin,
                    devanagariSession = devanagari,
                    latinCharacters = ModelConfig.parse(pack.latinConfig).characterList,
                    devanagariCharacters = ModelConfig.parse(pack.devanagariConfig).characterList,
                )
            } catch (error: Throwable) {
                devanagari.release()
                latin.release()
                throw error
            }
        }
    }
}

internal data class PaddleTensor(val data: FloatArray, val shape: LongArray)

internal object PaddleBitmapPipeline {
    private const val DETECTOR_MULTIPLE = 32
    private const val DETECTOR_MIN_SIDE = 64
    private const val DETECTOR_MAX_SIDE = 4000
    private const val RECOGNITION_HEIGHT = 48
    private const val RECOGNITION_MAX_WIDTH = 3200
    private val detectorMean = floatArrayOf(.485f, .456f, .406f)
    private val detectorStd = floatArrayOf(.229f, .224f, .225f)

    fun detectionTensor(source: Bitmap): PaddleTensor {
        val dimensions = detectorDimensions(source.width, source.height)
        val resized = if (source.width == dimensions.first && source.height == dimensions.second) {
            source
        } else {
            Bitmap.createScaledBitmap(source, dimensions.first, dimensions.second, true)
        }
        return try {
            val width = resized.width
            val height = resized.height
            val pixels = IntArray(width * height)
            resized.getPixels(pixels, 0, width, 0, 0, width, height)
            val planeSize = width * height
            val tensor = FloatArray(3 * planeSize)
            pixels.forEachIndexed { index, color ->
                // The official detection config decodes BGR input before normalization.
                val channels = intArrayOf(color and 0xff, color shr 8 and 0xff, color shr 16 and 0xff)
                repeat(3) { channel ->
                    tensor[channel * planeSize + index] =
                        (channels[channel] / 255f - detectorMean[channel]) / detectorStd[channel]
                }
            }
            PaddleTensor(tensor, longArrayOf(1, 3, height.toLong(), width.toLong()))
        } finally {
            if (resized !== source) resized.recycle()
        }
    }

    fun recognitionTensor(source: Bitmap): PaddleTensor {
        val width = ceil(RECOGNITION_HEIGHT * source.width.toDouble() / source.height.coerceAtLeast(1))
            .toInt().coerceIn(1, RECOGNITION_MAX_WIDTH)
        val resized = Bitmap.createScaledBitmap(source, width, RECOGNITION_HEIGHT, true)
        return try {
            val pixels = IntArray(width * RECOGNITION_HEIGHT)
            resized.getPixels(pixels, 0, width, 0, 0, width, RECOGNITION_HEIGHT)
            val planeSize = pixels.size
            val tensor = FloatArray(3 * planeSize)
            pixels.forEachIndexed { index, color ->
                tensor[index] = ((color shr 16 and 0xff) / 127.5f) - 1f
                tensor[planeSize + index] = ((color shr 8 and 0xff) / 127.5f) - 1f
                tensor[2 * planeSize + index] = ((color and 0xff) / 127.5f) - 1f
            }
            PaddleTensor(tensor, longArrayOf(1, 3, RECOGNITION_HEIGHT.toLong(), width.toLong()))
        } finally {
            resized.recycle()
        }
    }

    internal fun detectorDimensions(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        var ratio = if (minOf(width, height) < DETECTOR_MIN_SIDE) {
            DETECTOR_MIN_SIDE.toDouble() / minOf(width, height)
        } else 1.0
        var resizedWidth = (width * ratio).toInt()
        var resizedHeight = (height * ratio).toInt()
        if (maxOf(resizedWidth, resizedHeight) > DETECTOR_MAX_SIDE) {
            ratio = DETECTOR_MAX_SIDE.toDouble() / maxOf(resizedWidth, resizedHeight)
            resizedWidth = (resizedWidth * ratio).toInt()
            resizedHeight = (resizedHeight * ratio).toInt()
        }
        return roundToDetectorMultiple(resizedWidth) to roundToDetectorMultiple(resizedHeight)
    }

    private fun roundToDetectorMultiple(value: Int): Int =
        (round(value / DETECTOR_MULTIPLE.toDouble()).toInt() * DETECTOR_MULTIPLE).coerceAtLeast(DETECTOR_MULTIPLE)
}

internal data class PaddleTextBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** Bounded DB probability-map postprocessing using connected components instead of native OpenCV contours. */
internal object PaddleDbPostProcessor {
    private const val PIXEL_THRESHOLD = .3f
    private const val BOX_THRESHOLD = .6f
    private const val UNCLIP_RATIO = 1.5f
    private const val MAX_CANDIDATES = 512
    private const val MIN_MAP_SIDE = 3
    private const val MIN_OUTPUT_SIDE = 4

    fun extract(
        probabilities: FloatArray,
        outputShape: LongArray,
        originalWidth: Int,
        originalHeight: Int,
    ): List<PaddleTextBox> {
        require(outputShape.size >= 2) { "Detector output shape is invalid" }
        val mapHeight = outputShape[outputShape.lastIndex - 1].toInt()
        val mapWidth = outputShape.last().toInt()
        val mapSize = mapWidth * mapHeight
        require(mapWidth > 0 && mapHeight > 0 && probabilities.size >= mapSize) { "Detector output is incomplete" }
        val state = ByteArray(mapSize) { index -> if (probabilities[index] >= PIXEL_THRESHOLD) 1 else 0 }
        val queue = IntArray(mapSize)
        val scaleX = originalWidth.toDouble() / mapWidth
        val scaleY = originalHeight.toDouble() / mapHeight
        val boxes = mutableListOf<PaddleTextBox>()

        for (seed in state.indices) {
            if (state[seed].toInt() != 1) continue
            var head = 0
            var tail = 0
            queue[tail++] = seed
            state[seed] = 2
            var minX = seed % mapWidth
            var maxX = minX
            var minY = seed / mapWidth
            var maxY = minY
            var score = 0.0
            var count = 0
            while (head < tail) {
                val index = queue[head++]
                val x = index % mapWidth
                val y = index / mapWidth
                minX = minOf(minX, x)
                maxX = maxOf(maxX, x)
                minY = minOf(minY, y)
                maxY = maxOf(maxY, y)
                score += probabilities[index]
                count++
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nextX = x + dx
                    val nextY = y + dy
                    if (nextX !in 0 until mapWidth || nextY !in 0 until mapHeight) continue
                    val next = nextY * mapWidth + nextX
                    if (state[next].toInt() == 1) {
                        state[next] = 2
                        queue[tail++] = next
                    }
                }
            }
            val componentWidth = maxX - minX + 1
            val componentHeight = maxY - minY + 1
            if (componentWidth < MIN_MAP_SIDE || componentHeight < MIN_MAP_SIDE || score / count < BOX_THRESHOLD) continue
            val padding = ((componentWidth * componentHeight * UNCLIP_RATIO) /
                (2f * (componentWidth + componentHeight))).toInt().coerceAtLeast(1)
            val left = round((minX - padding).coerceAtLeast(0) * scaleX).toInt().coerceIn(0, originalWidth - 1)
            val top = round((minY - padding).coerceAtLeast(0) * scaleY).toInt().coerceIn(0, originalHeight - 1)
            val right = round((maxX + padding + 1).coerceAtMost(mapWidth) * scaleX).toInt().coerceIn(left + 1, originalWidth)
            val bottom = round((maxY + padding + 1).coerceAtMost(mapHeight) * scaleY).toInt().coerceIn(top + 1, originalHeight)
            if (right - left >= MIN_OUTPUT_SIDE && bottom - top >= MIN_OUTPUT_SIDE) {
                boxes += PaddleTextBox(left, top, right, bottom)
                if (boxes.size >= MAX_CANDIDATES) break
            }
        }
        return boxes.sortedWith(compareBy<PaddleTextBox> { it.top }.thenBy { it.left })
    }
}
