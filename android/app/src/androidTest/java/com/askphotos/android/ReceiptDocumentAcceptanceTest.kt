package com.askphotos.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiptDocumentAcceptanceTest {
    @Test
    fun latestSyntheticSwiggyTotalComesFromStoredOcrEntityAndRegion() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val runId = InstrumentationRegistry.getArguments().getString("galleryRunId")
        assumeTrue("galleryRunId was not supplied", !runId.isNullOrBlank())
        val repository = (instrumentation.targetContext.applicationContext as AskPhotosApplication).repository

        val deadline = System.currentTimeMillis() + INDEX_TIMEOUT_MS
        while (repository.pendingItems(1).isNotEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(250)
        }
        assertTrue("Seeded gallery did not finish indexing within the acceptance bound", repository.pendingItems(1).isEmpty())

        val outcome = repository.search("What was the total on my latest Swiggy receipt?")
        val receipt = requireNotNull(outcome.hits.firstOrNull { it.item.filename == "synthetic_swiggy_receipt.png" }) {
            "Synthetic Swiggy receipt was not returned"
        }
        val total = requireNotNull(receipt.evidence.firstOrNull { it.sourceField == "document_total" }) {
            "No deterministic document_total evidence was returned"
        }
        assertEquals("INR 1,248", total.text.uppercase())
        assertEquals("document-facts-v2", total.producerVersion)
        val region = requireNotNull(total.region)
        assertEquals(4, region.size)
        assertTrue(region.all { it in 0f..1f })
        assertEquals(listOf(total.id), outcome.answer.evidenceIds)
        assertEquals(ResultExactness.EXACT, outcome.answer.exactness)
        assertEquals(SortSpec.CAPTURE_TIME_DESC, outcome.plan.sort)
    }

    private companion object {
        const val INDEX_TIMEOUT_MS = 300_000L
    }
}
