package com.askphotos.android

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.Closeable
import kotlinx.coroutines.tasks.await
import kotlin.math.sqrt

/** Bundled, offline face-box detector. It deliberately emits no identity embedding. */
class MlKitFaceDetectionEngine : Closeable {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(.1f)
            .build(),
    )

    suspend fun detect(jpeg: ByteArray): List<FaceDetectionRecord> {
        require(jpeg.isNotEmpty() && jpeg.size <= MAX_INPUT_BYTES) { "Invalid face-detection image" }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)) { "Face image could not be decoded" }
        return try {
            val width = bitmap.width.toFloat()
            val height = bitmap.height.toFloat()
            detector.process(InputImage.fromBitmap(bitmap, 0)).await()
                .filter { it.boundingBox.width() >= MIN_FACE_EDGE && it.boundingBox.height() >= MIN_FACE_EDGE }
                .sortedByDescending { it.boundingBox.width() * it.boundingBox.height() }
                .take(MAX_FACES_PER_MEDIA)
                .mapNotNull { face ->
                    val box = face.boundingBox
                    val left = (box.left / width).coerceIn(0f, 1f)
                    val top = (box.top / height).coerceIn(0f, 1f)
                    val right = (box.right / width).coerceIn(0f, 1f)
                    val bottom = (box.bottom / height).coerceIn(0f, 1f)
                    if (left >= right || top >= bottom) return@mapNotNull null
                    val areaRatio = (box.width().toFloat() * box.height() / (width * height)).coerceIn(0f, 1f)
                    FaceDetectionRecord(left, top, right, bottom, sqrt(areaRatio).coerceIn(0f, 1f))
                }
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() = detector.close()

    companion object {
        const val PRODUCER_VERSION = "mlkit-face-detection-v1"
        const val MAX_FACES_PER_MEDIA = 64
        private const val MIN_FACE_EDGE = 24
        private const val MAX_INPUT_BYTES = 8 * 1024 * 1024
    }
}
