package com.samsung.agenticgallery

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class RealSFaceAcceptanceTest {
    @Test
    fun downloadsPinnedModelAndEmbedsFacesFromLicensedFixture() = runBlocking {
        assumeTrue("Consumer build is required for the download acceptance", BuildConfig.ALLOW_MODEL_DOWNLOAD)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as AgenticGalleryApplication
        val services = application.services
        if (!services.faceModelPackManager.status().installed) {
            services.faceModelDownloader.enqueue()
            withTimeout(5 * 60_000L) {
                while (true) {
                    val progress = services.faceModelDownloader.progress()
                    if (progress.state == GemmaDownloadState.INSTALLED) break
                    check(progress.state != GemmaDownloadState.FAILED) { progress.error ?: "SFace download failed" }
                    kotlinx.coroutines.delay(500)
                }
            }
        }
        val installed = requireNotNull(services.faceModelPackManager.current())
        assertEquals(FaceModelCatalog.sface.sizeBytes, installed.file.length())
        assertEquals(FaceModelCatalog.sface.producerVersion, installed.spec.producerVersion)

        val fixture = requireNotNull(application.repository.allItems().firstOrNull { it.filename == "children_football_01_v0.jpg" }) {
            "Licensed children-football fixture is not retained on the device"
        }
        val modelImage = application.contentResolver.loadThumbnail(
            Uri.parse(requireNotNull(fixture.contentUri)),
            Size(1024, 1024),
            null,
        ).toModelImage()
        val pssBeforeKb = Debug.getPss()
        val started = SystemClock.elapsedRealtime()
        val first: List<FaceInstance>
        val second: List<FaceInstance>
        OpenCvSFaceEngine(services.faceModelPackManager).use { engine ->
            first = engine.detectAndEmbed(modelImage)
            second = engine.detectAndEmbed(modelImage)
        }
        val elapsedMs = SystemClock.elapsedRealtime() - started
        val pssAfterKb = Debug.getPss()
        assertTrue("ML Kit did not find an alignable face in the licensed fixture", first.isNotEmpty())
        assertEquals(first.size, second.size)
        first.forEach { face ->
            assertEquals(4, face.bounds.size)
            assertEquals(128, face.embedding.size)
            assertTrue(face.embedding.all { it.isFinite() })
            assertTrue(abs(dot(face.embedding, face.embedding) - 1f) < 1e-3f)
        }
        assertTrue("Repeated local inference was not deterministic", dot(first.first().embedding, second.first().embedding) > .999f)
        instrumentation.sendStatus(2, Bundle().apply {
            putString(
                "real_sface_trace",
                "REAL_SFACE faces=${first.size} elapsedMs=$elapsedMs pssBeforeKb=$pssBeforeKb pssAfterKb=$pssAfterKb " +
                    "runtime=onnxruntime-${installed.spec.packVersion} dimension=${installed.spec.embeddingDimension}",
            )
        })
    }

    private fun Bitmap.toModelImage(): ModelImage = try {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        val bytes = ByteArray(pixels.size * 3)
        pixels.forEachIndexed { index, color ->
            bytes[index * 3] = (color shr 16).toByte()
            bytes[index * 3 + 1] = (color shr 8).toByte()
            bytes[index * 3 + 2] = color.toByte()
        }
        ModelImage(bytes, width, height)
    } finally {
        recycle()
    }

    private fun dot(left: FloatArray, right: FloatArray): Float {
        var value = 0f
        left.indices.forEach { value += left[it] * right[it] }
        return value
    }
}
