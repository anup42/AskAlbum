package com.askphotos.android

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

class MlKitOcrEngine : OcrEngine {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognize(image: ModelImage): OcrDocument {
        val bitmap = image.toRgbBitmap()
        return try {
            val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            OcrDocument(
                blocks = result.textBlocks.mapNotNull { block ->
                    val box = block.boundingBox ?: return@mapNotNull null
                    OcrBlock(
                        text = block.text,
                        confidence = block.lines.mapNotNull { it.confidence }.average().takeUnless(Double::isNaN)?.toFloat() ?: .8f,
                        bounds = listOf(
                            box.left.toFloat() / image.width,
                            box.top.toFloat() / image.height,
                            box.right.toFloat() / image.width,
                            box.bottom.toFloat() / image.height,
                        ),
                        script = unicodeScript(block.text),
                    )
                },
                language = result.textBlocks.map { it.recognizedLanguage }.firstOrNull(String::isNotBlank),
            )
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() = recognizer.close()

    companion object {
        const val PRODUCER_VERSION = "mlkit-text-latin-v2"
    }
}

internal fun ModelImage.toRgbBitmap(): Bitmap {
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

internal fun unicodeScript(text: String): String? = when {
    text.any { it in '\u0900'..'\u097f' } -> "Deva"
    text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN } -> "Latn"
    else -> null
}
