package io.github.anup42.askalbum

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmbeddedSiglip2AcceptanceTest {
    @Test
    fun bundledArchiveVerifiesAndActivatesWithoutNetwork() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as AskAlbumApplication
        val provisioner = application.services.embeddedRetrievalModelProvisioner
        val spec = EmbeddedRetrievalModel.siglip2BaseQuantized
        val started = SystemClock.elapsedRealtime()

        provisioner.enqueueIfNeeded()
        withTimeout(12 * 60_000L) {
            while (true) {
                val progress = provisioner.progress()
                if (progress.state == GemmaDownloadState.FAILED) error(progress.error ?: "Embedded SigLIP2 installation failed")
                if (progress.state == GemmaDownloadState.INSTALLED) break
                delay(1_000)
            }
        }

        val status = application.services.retrievalModelPackManager.status()
        val installed = requireNotNull(application.services.retrievalModelPackManager.current())
        assertTrue(status.installed)
        assertEquals(spec.packId, status.packId)
        assertEquals(spec.packVersion, status.packVersion)
        assertEquals(spec.sourceRevision, installed.manifest.sourceRevision)
        assertEquals(spec.artifactRevision, installed.manifest.artifactRevision)
        assertEquals(spec.installedBytes, status.installedBytes)
        installed.manifest.files.forEach { artifact ->
            assertEquals(artifact.sizeBytes, installed.artifact(artifact.role).length())
        }

        instrumentation.sendStatus(2, Bundle().apply {
            putString(
                "embedded_siglip2_trace",
                "EMBEDDED_SIGLIP2 pack=${spec.packId}@${spec.packVersion} " +
                    "archiveBytes=${spec.archiveSizeBytes} installedBytes=${spec.installedBytes} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - started}",
            )
        })
    }
}
