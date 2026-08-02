package io.github.anup42.askalbum

import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StressSeededGalleryTest {
    @Test
    fun runScopedStressImagesAreExactlyVisibleAndAppOwned() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val runId = InstrumentationRegistry.getArguments().getString("galleryRunId")
        val expected = InstrumentationRegistry.getArguments().getString("galleryExpectedCount")?.toIntOrNull()
        assumeTrue("galleryRunId was not supplied", !runId.isNullOrBlank())
        assumeTrue("galleryExpectedCount was not supplied", expected != null)
        val relativePath = "Pictures/AskAlbumTest/$runId/"
        val resolver = instrumentation.targetContext.contentResolver
        val count = resolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
            arrayOf(relativePath, instrumentation.targetContext.packageName),
            null,
        )?.use { it.count } ?: 0
        assertEquals(expected, count)
    }
}
