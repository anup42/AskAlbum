package com.askphotos.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndexRecoveryTest {
    @Test
    fun recoveredRunHasUniqueRowsAndCompleteStageCoverage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.getString("galleryRunId")
        assumeTrue("galleryRunId was not supplied", !runId.isNullOrBlank())
        val expected = arguments.getString("galleryExpectedCount")?.toIntOrNull()
        assumeTrue("galleryExpectedCount was not supplied", expected != null)
        val relativePaths = setOf(
            "Pictures/AgenticGalleryTest/$runId/",
            "Documents/AgenticGalleryTest/$runId/",
        )
        val repository = (instrumentation.targetContext.applicationContext as AskPhotosApplication).repository
        val items = repository.allItems().filter { item ->
            item.contentUri?.let(android.net.Uri::parse)?.let { uri ->
                instrumentation.targetContext.contentResolver.query(
                    uri,
                    arrayOf(android.provider.MediaStore.MediaColumns.RELATIVE_PATH),
                    null,
                    null,
                    null,
                )?.use { cursor -> cursor.moveToFirst() && cursor.getString(0) in relativePaths }
            } == true
        }
        assertEquals(expected, items.size)
        assertEquals(expected, items.map { it.id }.toSet().size)
        items.forEach { item ->
            val stages = repository.stageRecords(item.id)
            assertEquals(IndexStage.entries.size, stages.size)
            assertFalse(stages.any { it.status == StageStatus.RUNNING })
            assertFalse(item.indexState == IndexState.INDEXING)
            assertEquals(StageStatus.SKIPPED, stages.single { it.stage == IndexStage.FACES }.status)
        }
    }
}
