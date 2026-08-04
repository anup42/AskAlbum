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
}
