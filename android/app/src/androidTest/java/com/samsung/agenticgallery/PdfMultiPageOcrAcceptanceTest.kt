package com.samsung.agenticgallery

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfMultiPageOcrAcceptanceTest {
    @Test
    fun bothPagesAreOcrIndexedWithPageSpecificEvidence() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val runId = InstrumentationRegistry.getArguments().getString("galleryRunId")
        assumeTrue("galleryRunId was not supplied", !runId.isNullOrBlank())
        val context = instrumentation.targetContext
        val repository = (context.applicationContext as AgenticGalleryApplication).repository
        val pdf = requireNotNull(repository.allItems().firstOrNull { it.filename == PDF_FILENAME }) {
            "Seeded two-page PDF was not imported"
        }

        repository.requestGalleryReindex(setOf(pdf.id))
        GalleryIndexBatchProcessor(context, repository).use { processor ->
            val result = processor.processBatch(setOf(pdf.id), limit = 1)
            assertEquals(1, result.processed)
            assertEquals(0, result.retryableFailures)
            assertEquals(0, result.permanentFailures)
        }

        val blocks = repository.ocrBlocks(pdf.id)
        val firstPage = blocks.filter { it.pageIndex == 0 }.joinToString(" ") { it.normalizedText }
        val secondPage = blocks.filter { it.pageIndex == 1 }.joinToString(" ") { it.normalizedText }
        assertTrue("Page 1 reference was not indexed: $firstPage", "pdf-test-204" in firstPage)
        assertTrue("Page 2 fact was not indexed: $secondPage", "evidence stays on device" in secondPage)

        val outcome = repository.search("What known fact is written on page 2 of my PDF?")
        val hit = requireNotNull(outcome.hits.firstOrNull { it.item.id == pdf.id }) {
            "Page-2 query did not return the seeded PDF"
        }
        val evidence = requireNotNull(hit.evidence.firstOrNull { it.sourceField == "ocr_text" && it.pageIndex == 1 }) {
            "Page-2 OCR evidence was not preserved"
        }
        assertTrue("evidence stays on device" in evidence.text.lowercase())
        assertTrue(evidence.id in outcome.answer.evidenceIds)
    }

    private companion object {
        const val PDF_FILENAME = "synthetic_two_page_document.pdf"
    }
}
