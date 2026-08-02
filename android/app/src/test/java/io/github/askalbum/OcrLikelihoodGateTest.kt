package io.github.anup42.askalbum

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrLikelihoodGateTest {
    @Test
    fun ordinaryFlatPhotoIsSkipped() {
        val item = item("family_photo.jpg")
        val pixels = IntArray(640 * 480) { 0xff809060.toInt() }

        assertFalse(OcrLikelihoodGate.decide(item, listOf("people", "outdoor"), pixels, 640, 480).shouldRun)
    }

    @Test
    fun receiptFilenameAndPdfAlwaysRetainOcr() {
        val pixels = IntArray(100) { 0xffffffff.toInt() }

        assertTrue(OcrLikelihoodGate.decide(item("synthetic_swiggy_receipt.png"), emptyList(), pixels, 10, 10).shouldRun)
        assertTrue(OcrLikelihoodGate.decide(item("ticket.pdf", MediaKind.PDF), emptyList(), pixels, 10, 10).shouldRun)
    }

    private fun item(filename: String, kind: MediaKind = MediaKind.IMAGE) = GalleryItem(
        id = filename,
        filename = filename,
        title = filename,
        creator = null,
        location = "",
        latitude = null,
        longitude = null,
        tags = emptyList(),
        description = "",
        license = "",
        sourceUrl = "",
        assetPath = null,
        kind = kind,
    )
}
