package com.samsung.agenticgallery

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Base64
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Real dual-encoder inference over an installed, signature-verified retrieval pack.
 * Converted encoders have exactly one input and one FLOAT32 embedding output.
 */
class LiteRtImageTextEmbeddingEngine(
    private val modelPacks: RetrievalModelPackManager,
    private val resources: InferenceResourceManager,
) : ImageTextEmbeddingEngine {
    @Volatile private var cachedTokenizer: CachedTokenizer? = null
    internal val onnxInferenceThreads = EmbeddingInferencePolicy.onnxThreads(
        Runtime.getRuntime().availableProcessors(),
    )

    override suspend fun embedImage(image: ModelImage): FloatArray = embedImages(listOf(image)).single()

    internal suspend fun embedImages(images: List<ModelImage>): List<FloatArray> =
        resources.withModel(ModelCapability.IMAGE_EMBEDDING) {
            withContext(Dispatchers.Default) {
                val pack = modelPacks.current() ?: error("No verified retrieval model pack is installed")
                val pixels = images.map { Siglip2ImagePreprocessor.preprocess(it, pack.manifest) }
                when (pack.manifest.runtime) {
                    RETRIEVAL_RUNTIME_LITERT -> runLiteRtFloatModels(pack.artifact(ROLE_IMAGE_ENCODER), pixels, pack.manifest.embeddingDimension)
                    RETRIEVAL_RUNTIME_ONNX -> runOnnxImageModels(pack.artifact(ROLE_IMAGE_ENCODER), pixels, pack.manifest)
                    else -> error("Unsupported retrieval runtime")
                }
            }
        }

    override suspend fun embedText(text: String): FloatArray = embedTexts(listOf(text)).single()

    internal suspend fun embedTexts(texts: List<String>): List<FloatArray> =
        resources.withModel(ModelCapability.TEXT_EMBEDDING) {
            withContext(Dispatchers.Default) {
                val pack = modelPacks.current() ?: error("No verified retrieval model pack is installed")
                val tokenizer = tokenizerFor(pack)
                texts.map { text ->
                    val tokenIds = tokenizer.encode(text, pack.manifest)
                    when (pack.manifest.runtime) {
                        RETRIEVAL_RUNTIME_LITERT -> runLiteRtTextModel(
                            modelFile = pack.artifact(ROLE_TEXT_ENCODER),
                            tokenIds = tokenIds,
                            inputType = pack.manifest.textInputType,
                            dimension = pack.manifest.embeddingDimension,
                        )
                        RETRIEVAL_RUNTIME_ONNX -> runOnnxTextModel(pack.artifact(ROLE_TEXT_ENCODER), tokenIds, pack.manifest)
                        else -> error("Unsupported retrieval runtime")
                    }
                }
            }
        }

    private fun tokenizerFor(pack: InstalledRetrievalPack): Siglip2VocabTokenizer {
        val key = pack.directory.absolutePath
        cachedTokenizer?.takeIf { it.key == key }?.let { return it.tokenizer }
        return Siglip2VocabTokenizer.load(pack.artifact(ROLE_TOKENIZER)).also {
            cachedTokenizer = CachedTokenizer(key, it)
        }
    }

    private fun runLiteRtFloatModels(modelFile: File, inputs: List<FloatArray>, dimension: Int): List<FloatArray> {
        require(modelFile.isFile) { "LiteRT encoder artifact is unavailable" }
        Environment.create().use { environment ->
            CompiledModel.create(modelFile.absolutePath, CompiledModel.Options(Accelerator.CPU), environment).use { model ->
                return inputs.map { values ->
                    val inputBuffers = model.createInputBuffers()
                    val outputBuffers = model.createOutputBuffers()
                    require(inputBuffers.size == 1 && outputBuffers.size == 1) { "Encoder must have one input and one output" }
                    try {
                        inputBuffers.single().writeFloat(values)
                        model.run(inputBuffers, outputBuffers)
                        normalizeEmbedding(outputBuffers.single().readFloat(), dimension)
                    } finally {
                        inputBuffers.forEach { it.close() }
                        outputBuffers.forEach { it.close() }
                    }
                }
            }
        }
    }

    private fun runLiteRtTextModel(modelFile: File, tokenIds: IntArray, inputType: String, dimension: Int): FloatArray =
        runLiteRtModel(modelFile, dimension) { buffer ->
            when (inputType) {
                "INT32" -> buffer.writeInt(tokenIds)
                "INT64" -> buffer.writeLong(LongArray(tokenIds.size) { tokenIds[it].toLong() })
                else -> error("Unsupported text input type")
            }
        }

    private inline fun runLiteRtModel(
        modelFile: File,
        dimension: Int,
        writeInput: (TensorBuffer) -> Unit,
    ): FloatArray {
        require(modelFile.isFile) { "LiteRT encoder artifact is unavailable" }
        Environment.create().use { environment ->
            CompiledModel.create(
                modelFile.absolutePath,
                CompiledModel.Options(Accelerator.CPU),
                environment,
            ).use { model ->
                val inputs = model.createInputBuffers()
                val outputs = model.createOutputBuffers()
                require(inputs.size == 1 && outputs.size == 1) { "Encoder must have one input and one output" }
                try {
                    writeInput(inputs.single())
                    model.run(inputs, outputs)
                    return normalizeEmbedding(outputs.single().readFloat(), dimension)
                } finally {
                    inputs.forEach { it.close() }
                    outputs.forEach { it.close() }
                }
            }
        }
    }

    private fun runOnnxImageModels(
        modelFile: File,
        inputs: List<FloatArray>,
        manifest: RetrievalPackManifest,
    ): List<FloatArray> {
        require(modelFile.isFile) { "ONNX image encoder artifact is unavailable" }
        val environment = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(onnxInferenceThreads)
            environment.createSession(modelFile.absolutePath, options).use { session ->
                require(session.inputNames == setOf(ONNX_IMAGE_INPUT)) { "Unexpected ONNX image inputs" }
                require(ONNX_POOLER_OUTPUT in session.outputNames) { "ONNX image encoder has no pooler output" }
                val shape = longArrayOf(1, 3, manifest.imageSize.toLong(), manifest.imageSize.toLong())
                return inputs.map { values ->
                    OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), shape).use { input ->
                        session.run(mapOf(ONNX_IMAGE_INPUT to input)).use { result ->
                            normalizeEmbedding(readOnnxEmbedding(result, manifest.embeddingDimension), manifest.embeddingDimension)
                        }
                    }
                }
            }
        }
    }

    private fun runOnnxTextModel(
        modelFile: File,
        tokenIds: IntArray,
        manifest: RetrievalPackManifest,
    ): FloatArray {
        require(modelFile.isFile) { "ONNX text encoder artifact is unavailable" }
        require(manifest.textInputType == "INT64") { "ONNX SigLIP2 text input must be INT64" }
        val environment = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(onnxInferenceThreads)
            environment.createSession(modelFile.absolutePath, options).use { session ->
                require(session.inputNames == setOf(ONNX_TEXT_INPUT)) { "Unexpected ONNX text inputs" }
                require(ONNX_POOLER_OUTPUT in session.outputNames) { "ONNX text encoder has no pooler output" }
                val longs = LongArray(tokenIds.size) { tokenIds[it].toLong() }
                OnnxTensor.createTensor(environment, LongBuffer.wrap(longs), longArrayOf(1, tokenIds.size.toLong())).use { input ->
                    session.run(mapOf(ONNX_TEXT_INPUT to input)).use { result ->
                        return normalizeEmbedding(readOnnxEmbedding(result, manifest.embeddingDimension), manifest.embeddingDimension)
                    }
                }
            }
        }
    }

    private fun readOnnxEmbedding(result: OrtSession.Result, dimension: Int): FloatArray {
        val tensor = result.get(ONNX_POOLER_OUTPUT).orElseThrow { IllegalArgumentException("Missing ONNX pooler output") } as? OnnxTensor
            ?: error("ONNX pooler output is not a tensor")
        val buffer = tensor.floatBuffer
        require(buffer.remaining() == dimension) { "ONNX encoder returned the wrong embedding size" }
        return FloatArray(dimension).also(buffer::get)
    }

    private fun normalizeEmbedding(values: FloatArray, dimension: Int): FloatArray {
        require(values.size == dimension && values.all { it.isFinite() }) { "Encoder returned an invalid embedding" }
        var squaredNorm = 0.0
        values.forEach { squaredNorm += it.toDouble() * it.toDouble() }
        require(squaredNorm > 1e-18) { "Encoder returned a zero embedding" }
        val scale = (1.0 / sqrt(squaredNorm)).toFloat()
        return FloatArray(values.size) { values[it] * scale }
    }

    private data class CachedTokenizer(val key: String, val tokenizer: Siglip2VocabTokenizer)
}

internal object EmbeddingInferencePolicy {
    fun onnxThreads(availableProcessors: Int): Int = when {
        availableProcessors <= 2 -> 1
        availableProcessors <= 5 -> 2
        availableProcessors <= 7 -> 3
        else -> 4
    }
}

internal object Siglip2ImagePreprocessor {
    fun preprocess(image: ModelImage, manifest: RetrievalPackManifest): FloatArray {
        require(image.width > 0 && image.height > 0) { "Image dimensions must be positive" }
        require(image.rgbBytes.size == image.width * image.height * 3) { "ModelImage must contain packed RGB bytes" }
        val target = manifest.imageSize
        val output = FloatArray(target * target * 3)
        for (y in 0 until target) {
            val sourceY = (y + 0.5) * image.height / target - 0.5
            val yBase = floor(sourceY).toInt()
            for (x in 0 until target) {
                val sourceX = (x + 0.5) * image.width / target - 0.5
                val xBase = floor(sourceX).toInt()
                for (channel in 0..2) {
                    var weighted = 0.0
                    var weightSum = 0.0
                    for (dy in -1..2) {
                        val sy = (yBase + dy).coerceIn(0, image.height - 1)
                        val wy = cubic(sourceY - (yBase + dy))
                        for (dx in -1..2) {
                            val sx = (xBase + dx).coerceIn(0, image.width - 1)
                            val weight = wy * cubic(sourceX - (xBase + dx))
                            val unsigned = image.rgbBytes[(sy * image.width + sx) * 3 + channel].toInt() and 0xff
                            weighted += unsigned * weight
                            weightSum += weight
                        }
                    }
                    val unit = ((weighted / weightSum).coerceIn(0.0, 255.0) / 255.0).toFloat()
                    val destination = if (manifest.imageLayout == "NHWC") {
                        (y * target + x) * 3 + channel
                    } else {
                        channel * target * target + y * target + x
                    }
                    output[destination] = (unit - manifest.imageMean[channel]) / manifest.imageStd[channel]
                }
            }
        }
        return output
    }

    // Keys bicubic kernel (a = -0.5). Conversion tooling uses the same half-pixel convention.
    private fun cubic(value: Double): Double {
        val x = abs(value)
        return when {
            x <= 1.0 -> 1.5 * x * x * x - 2.5 * x * x + 1.0
            x < 2.0 -> -0.5 * x * x * x + 2.5 * x * x - 4.0 * x + 2.0
            else -> 0.0
        }
    }
}

/**
 * Compact export of the pinned Gemma/SigLIP2 SentencePiece vocabulary.
 * Format: AGTOK1 header, then id<TAB>score<TAB>base64(UTF-8 piece).
 */
internal class Siglip2VocabTokenizer private constructor(
    private val pieces: Map<String, Piece>,
    private val bytePieces: Map<Int, Int>,
    private val maxPieceChars: Int,
) {
    fun encode(text: String, manifest: RetrievalPackManifest): IntArray {
        // The pinned SigLIP2 model uses SentencePiece's identity normalizer with
        // add_dummy_prefix=false, remove_extra_whitespaces=false and
        // escape_whitespaces=true. Do not trim, collapse, or NFKC-normalize here.
        var normalized = if (manifest.lowercaseText) text.lowercase() else text
        normalized = normalized.replace(' ', '▁')

        val symbols = mutableListOf<Symbol>()
        var position = 0
        while (position < normalized.length) {
            val codePoint = normalized.codePointAt(position)
            val surface = String(Character.toChars(codePoint))
            val piece = pieces[surface]
            if (piece != null) {
                symbols += Symbol(surface, piece.id)
            } else {
                surface.toByteArray(Charsets.UTF_8).forEach { byte ->
                    symbols += Symbol(null, bytePieces[byte.toInt() and 0xff] ?: UNKNOWN_TOKEN_ID)
                }
            }
            position += Character.charCount(codePoint)
        }

        // This pinned tokenizer is SentencePiece BPE (model_type=2), not
        // unigram. Repeatedly merge the highest-scoring adjacent vocabulary
        // piece; equal scores are resolved left-to-right, matching SentencePiece.
        while (symbols.size > 1) {
            var bestIndex = -1
            var bestPiece: Piece? = null
            var bestSurface: String? = null
            for (index in 0 until symbols.lastIndex) {
                val left = symbols[index].surface ?: continue
                val right = symbols[index + 1].surface ?: continue
                if (left.length + right.length > maxPieceChars) continue
                val combined = left + right
                val candidate = pieces[combined] ?: continue
                if (bestPiece == null || candidate.score > bestPiece.score) {
                    bestIndex = index
                    bestPiece = candidate
                    bestSurface = combined
                }
            }
            if (bestIndex < 0) break
            symbols[bestIndex] = Symbol(requireNotNull(bestSurface), requireNotNull(bestPiece).id)
            symbols.removeAt(bestIndex + 1)
        }

        val ids = symbols.mapTo(mutableListOf(), Symbol::id)
        val usable = (manifest.textLength - 1).coerceAtLeast(0)
        if (ids.size > usable) ids.subList(usable, ids.size).clear()
        ids.add(manifest.eosTokenId)
        return IntArray(manifest.textLength) { index -> ids.getOrElse(index) { manifest.padTokenId } }
    }

    private data class Piece(val id: Int, val score: Double)
    private data class Symbol(val surface: String?, val id: Int)

    companion object {
        fun load(file: File): Siglip2VocabTokenizer {
            require(file.isFile) { "Tokenizer vocabulary is unavailable" }
            val pieces = HashMap<String, Piece>(280_000)
            val bytePieces = HashMap<Int, Int>(256)
            var maxChars = 1
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                val iterator = lines.iterator()
                require(iterator.hasNext() && iterator.next() == "AGTOK1") { "Unsupported tokenizer vocabulary" }
                var count = 0
                while (iterator.hasNext()) {
                    val columns = iterator.next().split('\t')
                    require(columns.size == 3) { "Malformed tokenizer vocabulary row" }
                    val id = columns[0].toInt()
                    val score = columns[1].toDouble()
                    val piece = Base64.getDecoder().decode(columns[2]).toString(Charsets.UTF_8)
                    val byteMatch = Regex("<0x([0-9A-Fa-f]{2})>").matchEntire(piece)
                    if (byteMatch != null) {
                        require(bytePieces.put(byteMatch.groupValues[1].toInt(16), id) == null) {
                            "Duplicate tokenizer byte piece"
                        }
                    } else if (id !in SPECIAL_TOKEN_IDS) {
                        require(pieces.put(piece, Piece(id, score)) == null) { "Duplicate tokenizer piece" }
                        maxChars = maxOf(maxChars, piece.length)
                    }
                    count++
                    require(count <= 300_000) { "Tokenizer vocabulary is too large" }
                }
                require(count >= 1_000) { "Tokenizer vocabulary is incomplete" }
                require(bytePieces.size == 256) { "Tokenizer byte fallback is incomplete" }
            }
            return Siglip2VocabTokenizer(pieces, bytePieces, maxChars)
        }
    }
}

private const val UNKNOWN_TOKEN_ID = 3
private val SPECIAL_TOKEN_IDS = setOf(0, 1, 2, 3)
private const val ONNX_IMAGE_INPUT = "pixel_values"
private const val ONNX_TEXT_INPUT = "input_ids"
private const val ONNX_POOLER_OUTPUT = "pooler_output"
