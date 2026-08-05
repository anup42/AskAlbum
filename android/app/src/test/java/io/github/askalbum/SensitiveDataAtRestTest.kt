package io.github.anup42.askalbum

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveDataAtRestTest {
    @Test
    fun onlyHighRiskOcrFieldsUseTheProtectedEnvelope() {
        assertTrue(OcrEntityType.PASSWORD.isHighRiskAtRest())
        assertTrue(OcrEntityType.EMAIL.isHighRiskAtRest())
        assertTrue(OcrEntityType.ORDER_ID.isHighRiskAtRest())
        assertFalse(OcrEntityType.DATE.isHighRiskAtRest())
        assertFalse(OcrEntityType.RECEIPT_TOTAL.isHighRiskAtRest())
    }

    @Test
    fun searchableOcrProjectionKeepsSafeTermsAndRemovesPrivateValues() {
        val projection = SensitiveContentClassifier.redactForSearch(
            "Wi-Fi password: mango-tree-2048. Receipt total Rs 1,248.",
        )

        assertTrue(projection.contains("password", ignoreCase = true))
        assertTrue(projection.contains("receipt total", ignoreCase = true))
        assertFalse(projection.contains("mango-tree-2048"))
    }
}
