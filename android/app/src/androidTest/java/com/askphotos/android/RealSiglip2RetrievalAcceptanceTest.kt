package com.askphotos.android

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealSiglip2RetrievalAcceptanceTest {
    @Test
    fun installedSignedQ8PackPreservesE2bAndRanksSyntheticAndRetainedImages() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as AskPhotosApplication
        val incoming = File(application.filesDir, "host-import/siglip2-base-p16-224-q8.agretrieval")
        val e2bBefore = application.modelPackManager.status()
        assertTrue("Verified E2B must remain installed", e2bBefore.installed && e2bBefore.tier == GemmaModelTier.E2B)

        try {
            val installed = if (incoming.isFile) {
                application.services.retrievalModelPackManager.installVerified(incoming)
            } else {
                requireNotNull(application.services.retrievalModelPackManager.current()) {
                    "No verified retrieval model pack is installed"
                }
            }
            val status = application.services.retrievalModelPackManager.status()
            assertTrue(status.installed)
            assertEquals("siglip2-base-p16-224-q8", status.packId)
            assertEquals("ba1f3b0-q8-core05", status.packVersion)
            assertEquals(768, status.embeddingDimension)
            assertEquals(RETRIEVAL_RUNTIME_ONNX, installed.manifest.runtime)
            assertEquals(ONNX_SIGLIP2_REPOSITORY, installed.manifest.artifactRepository)
            assertEquals(0.05f, installed.manifest.minimumSimilarity, 0f)

            val tokenizer = Siglip2VocabTokenizer.load(installed.artifact(ROLE_TOKENIZER))
            assertArrayEquals(
                intArrayOf(883, 603, 476, 2686, 576, 476, 3118, 7800, 235265, 1),
                tokenizer.encode("This is a photo of a red square.", installed.manifest).take(10).toIntArray(),
            )
            assertArrayEquals(
                intArrayOf(883, 603, 476, 2686, 576, 476, 3868, 7800, 235265, 1),
                tokenizer.encode("This is a photo of a blue square.", installed.manifest).take(10).toIntArray(),
            )

            val red = solidImage(255, 0, 0)
            val blue = solidImage(0, 0, 255)
            val pssBeforeKb = Debug.getPss()
            val started = SystemClock.elapsedRealtime()
            val embeddings = withTimeout(5 * 60_000L) {
                val engine = application.services.embeddingEngine
                listOf(
                    engine.embedImage(red),
                    engine.embedImage(blue),
                    engine.embedText("This is a photo of a red square."),
                    engine.embedText("This is a photo of a blue square."),
                )
            }
            val elapsedMs = SystemClock.elapsedRealtime() - started
            val pssAfterKb = Debug.getPss()
            embeddings.forEach { embedding ->
                assertEquals(768, embedding.size)
                assertTrue(embedding.all(Float::isFinite))
                assertTrue(abs(dot(embedding, embedding) - 1f) < 1e-3f)
            }
            val (redImage, blueImage, redText, blueText) = embeddings
            val redCorrect = dot(redImage, redText)
            val redWrong = dot(blueImage, redText)
            val blueCorrect = dot(blueImage, blueText)
            val blueWrong = dot(redImage, blueText)
            assertTrue("Red text did not prefer the red image: $redCorrect <= $redWrong", redCorrect > redWrong)
            assertTrue("Blue text did not prefer the blue image: $blueCorrect <= $blueWrong", blueCorrect > blueWrong)

            val retained = application.repository.allItems().associateBy(GalleryItem::filename)
            val dog = decodeModelImage(application, requireNotNull(retained["domesticated_dog_01_v0.jpg"]))
            val football = decodeModelImage(application, requireNotNull(retained["children_football_01_v0.jpg"]))
            val engine = application.services.embeddingEngine as LiteRtImageTextEmbeddingEngine
            val naturalImages = engine.embedImages(listOf(dog, football))
            val dogText = engine.embedText("a photo of a dog pet")
            val footballText = engine.embedText("children playing football outdoors")
            val dogCorrect = dot(naturalImages[0], dogText)
            val dogWrong = dot(naturalImages[1], dogText)
            val footballCorrect = dot(naturalImages[1], footballText)
            val footballWrong = dot(naturalImages[0], footballText)
            assertTrue("Dog query did not prefer the dog fixture: $dogCorrect <= $dogWrong", dogCorrect > dogWrong)
            assertTrue(
                "Football query did not prefer the football fixture: $footballCorrect <= $footballWrong",
                footballCorrect > footballWrong,
            )
            assertTrue(application.modelPackManager.status().installed)

            instrumentation.sendStatus(2, Bundle().apply {
                putString(
                    "real_siglip2_trace",
                    "REAL_SIGLIP2 runtime=${installed.manifest.runtime} revision=${installed.manifest.artifactRevision} " +
                        "elapsedMs=$elapsedMs pssBeforeKb=$pssBeforeKb pssAfterKb=$pssAfterKb " +
                        "redCorrect=$redCorrect redWrong=$redWrong blueCorrect=$blueCorrect blueWrong=$blueWrong " +
                        "dogCorrect=$dogCorrect dogWrong=$dogWrong " +
                        "footballCorrect=$footballCorrect footballWrong=$footballWrong",
                )
            })
        } finally {
            incoming.delete()
        }
    }

    private fun decodeModelImage(application: AskPhotosApplication, item: GalleryItem): ModelImage {
        val bitmap = application.contentResolver.loadThumbnail(
            Uri.parse(requireNotNull(item.contentUri)),
            Size(512, 512),
            null,
        )
        return bitmap.toModelImage()
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

    private fun solidImage(red: Int, green: Int, blue: Int): ModelImage {
        val bytes = ByteArray(224 * 224 * 3)
        for (offset in bytes.indices step 3) {
            bytes[offset] = red.toByte()
            bytes[offset + 1] = green.toByte()
            bytes[offset + 2] = blue.toByte()
        }
        return ModelImage(bytes, 224, 224)
    }

    private fun dot(left: FloatArray, right: FloatArray): Float {
        var sum = 0f
        for (index in left.indices) sum += left[index] * right[index]
        return sum
    }
}
