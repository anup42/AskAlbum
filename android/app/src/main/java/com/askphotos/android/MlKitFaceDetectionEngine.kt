package com.askphotos.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PointF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.Closeable
import kotlinx.coroutines.tasks.await
import kotlin.math.sqrt

internal data class FaceLandmarks5(
    val leftEye: PointF,
    val rightEye: PointF,
    val nose: PointF,
    val mouthLeft: PointF,
    val mouthRight: PointF,
) {
    fun points(): List<PointF> = listOf(leftEye, rightEye, nose, mouthLeft, mouthRight)
}

internal data class DetailedFaceDetection(
    val record: FaceDetectionRecord,
    val landmarks: FaceLandmarks5?,
)

/** Bundled, offline face detector. Five landmarks are retained only long enough for local SFace alignment. */
class MlKitFaceDetectionEngine : Closeable {
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(.1f)
            .build(),
    )

    suspend fun detect(jpeg: ByteArray): List<FaceDetectionRecord> {
        require(jpeg.isNotEmpty() && jpeg.size <= MAX_INPUT_BYTES) { "Invalid face-detection image" }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)) { "Face image could not be decoded" }
        return try {
            detectDetailed(bitmap).map(DetailedFaceDetection::record)
        } finally {
            bitmap.recycle()
        }
    }

    internal suspend fun detectDetailed(image: ModelImage): List<DetailedFaceDetection> {
        require(image.width > 0 && image.height > 0 && image.rgbBytes.size == image.width * image.height * 3) {
            "ModelImage must contain packed RGB bytes"
        }
        val pixels = IntArray(image.width * image.height) { index ->
            val offset = index * 3
            Color.rgb(
                image.rgbBytes[offset].toInt() and 0xff,
                image.rgbBytes[offset + 1].toInt() and 0xff,
                image.rgbBytes[offset + 2].toInt() and 0xff,
            )
        }
        val bitmap = Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
        return try {
            detectDetailed(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun detectDetailed(bitmap: Bitmap): List<DetailedFaceDetection> {
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        return detector.process(InputImage.fromBitmap(bitmap, 0)).await()
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
                    val record = FaceDetectionRecord(left, top, right, bottom, sqrt(areaRatio).coerceIn(0f, 1f))
                    val landmarks = listOf(
                        FaceLandmark.LEFT_EYE,
                        FaceLandmark.RIGHT_EYE,
                        FaceLandmark.NOSE_BASE,
                        FaceLandmark.MOUTH_LEFT,
                        FaceLandmark.MOUTH_RIGHT,
                    ).map { type -> face.getLandmark(type)?.position }
                    DetailedFaceDetection(
                        record,
                        landmarks.takeIf { points -> points.all { it != null } }?.let { points ->
                            FaceLandmarks5(points[0]!!, points[1]!!, points[2]!!, points[3]!!, points[4]!!)
                        },
                    )
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
