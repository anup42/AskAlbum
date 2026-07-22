package com.askphotos.android

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealPaddleOcrAcceptanceTest {
    @Test
    fun downloadsPinnedPackAndRecognizesLatinAndDevanagariLocally() = runBlocking {
        assumeTrue("Consumer build is required for the download acceptance", BuildConfig.ALLOW_MODEL_DOWNLOAD)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as AskPhotosApplication
        val services = application.services
        if (!services.ocrModelPackManager.status().installed) {
            services.ocrModelDownloader.enqueue()
            withTimeout(5 * 60_000L) {
                while (true) {
                    val progress = services.ocrModelDownloader.progress()
                    if (progress.state == GemmaDownloadState.INSTALLED) break
                    check(progress.state != GemmaDownloadState.FAILED) { progress.error ?: "PaddleOCR download failed" }
                    delay(500)
                }
            }
        }
        val installed = requireNotNull(services.ocrModelPackManager.current())
        assertEquals(OcrModelCatalog.paddleV5Multilingual.sizeBytes, installed.spec.sizeBytes)
        OcrModelCatalog.paddleV5Multilingual.artifacts.forEach { artifact ->
            assertEquals(artifact.sizeBytes, java.io.File(installed.root, artifact.targetName).length())
        }

        val fixture = requireNotNull(application.repository.allItems().firstOrNull { it.filename == "synthetic_menu_hindi_v0.png" }) {
            "The CC0 Hindi OCR fixture is not retained on the device"
        }
        val image = application.contentResolver.loadThumbnail(
            Uri.parse(requireNotNull(fixture.contentUri)),
            Size(1240, 1754),
            null,
        ).toModelImage()
        val pssBeforeKb = Debug.getPss()
        val started = SystemClock.elapsedRealtime()
        val result = services.ocrEngines.acquire().use { lease ->
            assertEquals("paddleocr-v5-multilingual", lease.descriptor.id)
            lease.engine.recognize(image)
        }
        val elapsedMs = SystemClock.elapsedRealtime() - started
        val text = result.blocks.joinToString(" ") { it.text }
        assertTrue("Latin recognizer did not recover the fixture heading: $text", text.contains("TEST", ignoreCase = true))
        assertTrue("Devanagari recognizer produced no Devanagari text: $text", text.any { it in '\u0900'..'\u097f' })
        instrumentation.sendStatus(2, Bundle().apply {
            putString(
                "real_paddleocr_trace",
                "REAL_PADDLEOCR blocks=${result.blocks.size} elapsedMs=$elapsedMs pssBeforeKb=$pssBeforeKb " +
                    "pssAfterKb=${Debug.getPss()} producer=${installed.spec.producerVersion}",
            )
        })
    }

    private fun Bitmap.toModelImage(): ModelImage = try {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        val rgb = ByteArray(pixels.size * 3)
        pixels.forEachIndexed { index, color ->
            rgb[index * 3] = (color shr 16).toByte()
            rgb[index * 3 + 1] = (color shr 8).toByte()
            rgb[index * 3 + 2] = color.toByte()
        }
        ModelImage(rgb, width, height)
    } finally {
        recycle()
    }
}
