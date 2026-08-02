package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentFactExtractorTest {
    @Test
    fun receiptTotalPrefersGrandTotalOverSubtotalTaxAndDiscount() {
        val blocks = listOf(
            block("Subtotal       INR 1,180", .45f),
            block("Tax               INR 118", .55f),
            block("Discount          INR 50", .62f),
            block("GRAND TOTAL     INR 1,248", .78f, left = .12f, right = .82f),
            block("Amount Paid      INR 1,248", .86f),
        )

        val total = requireNotNull(DocumentFactExtractor.receiptTotal(blocks))

        assertEquals(OcrEntityType.RECEIPT_TOTAL, total.type)
        assertEquals("INR 1,248", total.rawText)
        assertEquals("1248.00", total.normalizedValue)
        assertEquals("grand_total", total.label)
        assertEquals(listOf(.12f, .78f, .82f, .88f), listOf(total.left, total.top, total.right, total.bottom))
    }

    @Test
    fun extractsBoundedDocumentEntitiesWithoutInventingValues() {
        val entities = DocumentFactExtractor.extract(listOf(
            block("Order TEST-1842", .25f),
            block("Flight: AG 204", .35f),
            block("Date: 12 MAR 2024", .45f),
            block("help@example.test https://example.test", .55f),
            block("Password: mango-tree-2048", .65f),
        ))

        assertTrue(entities.any { it.type == OcrEntityType.ORDER_ID && it.normalizedValue == "TEST-1842" })
        assertTrue(entities.any { it.type == OcrEntityType.FLIGHT_NUMBER && it.normalizedValue == "AG204" })
        assertTrue(entities.any { it.type == OcrEntityType.DATE && it.normalizedValue == "12 MAR 2024" })
        assertTrue(entities.any { it.type == OcrEntityType.EMAIL && it.normalizedValue == "help@example.test" })
        assertTrue(entities.any { it.type == OcrEntityType.URL && it.normalizedValue == "https://example.test" })
        assertTrue(entities.any { it.type == OcrEntityType.PASSWORD && it.normalizedValue == "mango-tree-2048" })
    }

    private fun block(text: String, top: Float, left: Float = .1f, right: Float = .9f) = OcrBlockRecord(
        text = text,
        confidence = .96f,
        left = left,
        top = top,
        right = right,
        bottom = top + .1f,
    )
}
