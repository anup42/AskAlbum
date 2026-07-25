package com.askphotos.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class EmbeddedSFaceAcceptanceTest {
    @Test
    fun embeddedModelIsVerifiedAndActivatedInPrivateStorage() = runBlocking {
        val application = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as AskPhotosApplication
        val services = application.services

        services.embeddedFaceModelProvisioner.enqueueIfNeeded()
        withTimeout(2 * 60_000L) {
            while (!services.faceModelPackManager.status().installed) {
                val progress = services.embeddedFaceModelProvisioner.progress()
                check(progress.state != GemmaDownloadState.FAILED) {
                    progress.error ?: "Embedded SFace activation failed"
                }
                delay(250)
            }
        }

        val installed = requireNotNull(services.faceModelPackManager.current())
        assertEquals(FaceModelCatalog.sface.sizeBytes, installed.file.length())
        assertEquals(FaceModelCatalog.sface.sha256, sha256(installed.file))
        val assetLength = application.assets.openFd(EmbeddedFaceModel.ASSET_PATH).use { it.length }
        assertEquals(FaceModelCatalog.sface.sizeBytes, assetLength)
        assertTrue(installed.file.absolutePath.startsWith(application.filesDir.absolutePath))
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
