package com.askphotos.android

import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeededEventFollowUpAcceptanceTest {
    @Test
    fun singaporeEventSupportsTwoResultSetScopedRefinements() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val runId = InstrumentationRegistry.getArguments().getString("galleryRunId")
        assumeTrue("galleryRunId was not supplied", !runId.isNullOrBlank())
        val safeRunId = requireNotNull(runId)
        val repository = (instrumentation.targetContext.applicationContext as AskPhotosApplication).repository
        val relativePaths = setOf(
            "Pictures/AgenticGalleryTest/$safeRunId/",
            "Documents/AgenticGalleryTest/$safeRunId/",
        )

        val deadline = System.currentTimeMillis() + INDEX_TIMEOUT_MS
        var seeded = seededItems(repository, instrumentation.targetContext.contentResolver, relativePaths)
        while (
            (seeded.isEmpty() || seeded.any { it.indexState != IndexState.READY } ||
                seeded.any { item -> repository.stageRecords(item.id).none { it.stage == IndexStage.EVENTS && it.status == StageStatus.COMPLETE } }) &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(250)
            seeded = seededItems(repository, instrumentation.targetContext.contentResolver, relativePaths)
        }
        assertTrue("Seeded gallery was not ready within the acceptance bound", seeded.isNotEmpty() && seeded.all { it.indexState == IndexState.READY })
        assertTrue(
            "MediaStore DATE_TAKEN was not preserved for Singapore fixtures",
            seeded.filter { it.filename.contains("singapore", true) }.all { item ->
                item.capturedAt?.let { timestamp -> java.time.Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).year == 2024 } == true
            },
        )
        repository.rebuildEvents()

        val session = "event_${safeRunId.take(50)}"
        val trip = repository.searchInSession("Show photos from my Singapore trip.", session)
        val seededSingapore = trip.hits.filter { it.item.id in seeded.map(GalleryItem::id) && it.item.filename.contains("singapore", true) }
        assertTrue("Q01 did not return seeded Singapore media; terms=${trip.plan.terms}, hitCount=${trip.hits.size}", seededSingapore.isNotEmpty())
        assertTrue("Q01 did not carry compiled event evidence", seededSingapore.all { hit -> hit.evidence.any { it.sourceField == "event" } })
        assertNotNull(trip.resultSetId)

        val marinaBay = repository.searchInSession("Only Marina Bay.", session)
        assertEquals(trip.resultSetId, marinaBay.baseResultSetId)
        assertTrue("Q02 escaped its parent result set", marinaBay.hits.all { it.item.id in trip.hits.map(SearchHit::item).map(GalleryItem::id) })
        assertTrue("Q02 did not retain Marina Bay media", marinaBay.hits.any { it.item.filename.contains("singapore_marina_bay", true) })

        val lastYear = repository.searchInSession("What about last year?", session)
        assertEquals(marinaBay.resultSetId, lastYear.baseResultSetId)
        assertTrue(lastYear.plan.terms.isEmpty())
        assertTrue(lastYear.plan.semanticClauses.isEmpty())
        assertEquals(
            FilterExpression.TimeRange(
                LocalDate.of(2025, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                LocalDate.of(2026, 1, 1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1,
            ),
            lastYear.plan.filter,
        )
        assertTrue("Q03 fabricated a 2025 match from the 2024 Marina Bay result set", lastYear.hits.isEmpty())
        assertTrue(lastYear.answer.evidenceIds.isEmpty())
        assertEquals(ResultExactness.EXACT, lastYear.answer.exactness)
    }

    private fun seededItems(
        repository: GalleryRepository,
        resolver: android.content.ContentResolver,
        relativePaths: Set<String>,
    ): List<GalleryItem> = repository.allItems().filter { item ->
        item.contentUri?.let(Uri::parse)?.let { uri ->
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { cursor ->
                cursor.moveToFirst() && cursor.getString(0) in relativePaths
            }
        } == true
    }

    private companion object {
        const val INDEX_TIMEOUT_MS = 300_000L
    }
}
