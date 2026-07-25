package com.samsung.agenticgallery

import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeededGalleryTest {
    @Test
    fun runScopedSeedIsVisibleThroughMediaStore() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.getString("galleryRunId")
        assumeTrue("galleryRunId was not supplied", !runId.isNullOrBlank())
        val expected = arguments.getString("galleryExpectedCount")?.toIntOrNull()
        val relativePaths = listOf(
            "Pictures/AgenticGalleryTest/$runId/",
            "Documents/AgenticGalleryTest/$runId/",
        )
        val resolver = instrumentation.targetContext.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val counts = relativePaths.associateWith { relativePath ->
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(relativePath),
                null,
            )?.use { cursor -> cursor.count } ?: 0
        }
        val count = counts.values.sum()
        assertTrue("Expected seeded images/videos in ${relativePaths[0]}", requireNotNull(counts[relativePaths[0]]) > 0)
        assertTrue("Expected seeded PDFs in ${relativePaths[1]}", requireNotNull(counts[relativePaths[1]]) > 0)
        if (expected != null) assertEquals(expected, count)
    }
}
