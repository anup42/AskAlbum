package com.samsung.agenticgallery

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoFabricationAcceptanceTest {
    @Test
    fun nonexistentMerchantReturnsNoMatchWithNoClaimsOrEvidence() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val runId = InstrumentationRegistry.getArguments().getString("galleryRunId")
        assumeTrue("galleryRunId was not supplied", !runId.isNullOrBlank())
        val repository = (instrumentation.targetContext.applicationContext as AgenticGalleryApplication).repository
        val deadline = SystemClock.elapsedRealtime() + 120_000L
        while (repository.pendingItems(1).isNotEmpty() && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(250)
        }
        assertTrue("Seeded gallery did not finish indexing within the acceptance bound", repository.pendingItems(1).isEmpty())

        val outcome = repository.search("Show a receipt from a merchant that does not exist.")

        assertEquals(QueryIntent.DOCUMENT_QA, outcome.plan.intent)
        assertTrue(outcome.plan.ocrClause?.merchant?.contains("does not exist", ignoreCase = true) == true)
        assertTrue(outcome.hits.isEmpty())
        assertEquals("No supported matches found", outcome.answer.headline)
        assertTrue(outcome.answer.evidenceIds.isEmpty())
        assertTrue(outcome.answer.claims.isEmpty())
        assertTrue(outcome.answer.exactness in setOf(ResultExactness.COMPLETE_MODEL_SCAN, ResultExactness.PARTIAL_INDEX))
    }
}
