package io.github.anup42.askalbum

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.sqrt

/** Apache-2.0 OpenCV SFace MobileFaceNet executed locally with the already-pinned ONNX Runtime. */
class OpenCvSFaceEngine(
    private val modelPacks: FaceModelPackManager,
) : FaceEngine, Closeable {
    private val detector = MlKitFaceDetectionEngine()
    private val environment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null
    private var loadedVersion: String? = null

    override suspend fun detectAndEmbed(image: ModelImage): List<FaceInstance> {
        val installed = modelPacks.current() ?: error("OpenCV SFace is not installed")
        val activeSession = session(installed)
        return detector.detectDetailed(image).mapNotNull { face ->
            val landmarks = face.landmarks ?: return@mapNotNull null
            val aligned = SFaceImagePreprocessor.alignAndPreprocess(image, landmarks, installed.spec.inputSize)
            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(aligned),
                longArrayOf(1, 3, installed.spec.inputSize.toLong(), installed.spec.inputSize.toLong()),
            ).use { input ->
                activeSession.run(mapOf(INPUT_NAME to input)).use { output ->
                    val tensor = output.get(OUTPUT_NAME).orElseThrow { IllegalArgumentException("SFace output is missing") } as? OnnxTensor
                        ?: error("SFace output is not a tensor")
                    val buffer = tensor.floatBuffer
                    require(buffer.remaining() == installed.spec.embeddingDimension) { "SFace returned the wrong embedding size" }
                    val embedding = FloatArray(installed.spec.embeddingDimension).also(buffer::get).l2Normalized()
                    val box = face.record
                    FaceInstance(listOf(box.left, box.top, box.right, box.bottom), embedding, box.quality)
                }
            }
        }
    }

    private fun session(installed: InstalledFaceModel): OrtSession {
        val version = installed.spec.producerVersion
        session?.takeIf { loadedVersion == version }?.let { return it }
        session?.close()
        val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
        return environment.createSession(installed.file.absolutePath, options).also { created ->
            options.close()
            require(created.inputNames == setOf(INPUT_NAME)) { "Unexpected SFace input contract" }
            require(OUTPUT_NAME in created.outputNames) { "Unexpected SFace output contract" }
            session = created
            loadedVersion = version
        }
    }

    override fun close() {
        session?.close()
        session = null
        detector.close()
    }

    private fun FloatArray.l2Normalized(): FloatArray {
        require(all { it.isFinite() }) { "SFace returned a non-finite embedding" }
        val norm = sqrt(sumOf { it.toDouble() * it.toDouble() })
        require(norm > 1e-9) { "SFace returned a zero embedding" }
        return FloatArray(size) { this[it] / norm.toFloat() }
    }

    companion object {
        private const val INPUT_NAME = "data"
        private const val OUTPUT_NAME = "fc1"
    }
}

internal object SFaceImagePreprocessor {
    private val destination = listOf(
        PointF(38.2946f, 51.6963f),
        PointF(73.5318f, 51.5014f),
        PointF(56.0252f, 71.7366f),
        PointF(41.5493f, 92.3655f),
        PointF(70.7299f, 92.2041f),
    )

    fun alignAndPreprocess(image: ModelImage, landmarks: FaceLandmarks5, targetSize: Int): FloatArray {
        require(targetSize == 112) { "OpenCV SFace requires 112x112 aligned faces" }
        val sourceBitmap = image.toBitmap()
        val aligned = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        try {
            val transform = similarityTransform(landmarks.points(), destination)
            Canvas(aligned).drawBitmap(sourceBitmap, transform, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            val pixels = IntArray(targetSize * targetSize)
            aligned.getPixels(pixels, 0, targetSize, 0, 0, targetSize, targetSize)
            val plane = targetSize * targetSize
            return FloatArray(plane * 3).also { output ->
                pixels.forEachIndexed { index, pixel ->
                    output[index] = Color.red(pixel).toFloat()
                    output[plane + index] = Color.green(pixel).toFloat()
                    output[2 * plane + index] = Color.blue(pixel).toFloat()
                }
            }
        } finally {
            sourceBitmap.recycle()
            aligned.recycle()
        }
    }

    internal fun similarityTransform(source: List<PointF>, target: List<PointF>): Matrix {
        require(source.size == 5 && target.size == 5)
        val sourceX = source.sumOf { it.x.toDouble() } / source.size
        val sourceY = source.sumOf { it.y.toDouble() } / source.size
        val targetX = target.sumOf { it.x.toDouble() } / target.size
        val targetY = target.sumOf { it.y.toDouble() } / target.size
        var denominator = 0.0
        var real = 0.0
        var imaginary = 0.0
        source.indices.forEach { index ->
            val sx = source[index].x - sourceX
            val sy = source[index].y - sourceY
            val tx = target[index].x - targetX
            val ty = target[index].y - targetY
            denominator += sx * sx + sy * sy
            real += sx * tx + sy * ty
            imaginary += sx * ty - sy * tx
        }
        require(denominator > 1e-6) { "Face landmarks are degenerate" }
        val a = real / denominator
        val b = imaginary / denominator
        val translateX = targetX - a * sourceX + b * sourceY
        val translateY = targetY - b * sourceX - a * sourceY
        return Matrix().apply {
            setValues(floatArrayOf(
                a.toFloat(), (-b).toFloat(), translateX.toFloat(),
                b.toFloat(), a.toFloat(), translateY.toFloat(),
                0f, 0f, 1f,
            ))
        }
    }

    private fun ModelImage.toBitmap(): Bitmap {
        require(width > 0 && height > 0 && rgbBytes.size == width * height * 3) { "ModelImage must contain packed RGB bytes" }
        val pixels = IntArray(width * height) { index ->
            val offset = index * 3
            Color.rgb(
                rgbBytes[offset].toInt() and 0xff,
                rgbBytes[offset + 1].toInt() and 0xff,
                rgbBytes[offset + 2].toInt() and 0xff,
            )
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
